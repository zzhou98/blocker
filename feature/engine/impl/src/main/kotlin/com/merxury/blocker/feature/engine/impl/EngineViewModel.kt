/*
 * Copyright 2025 Blocker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 */

package com.merxury.blocker.feature.engine.impl

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.merxury.blocker.core.controllers.IController
import com.merxury.blocker.core.controllers.di.IfwControl
import com.merxury.blocker.core.controllers.di.RootApiControl
import com.merxury.blocker.core.controllers.di.ShizukuControl
import com.merxury.blocker.core.data.respository.app.AppRepository
import com.merxury.blocker.core.data.respository.component.ComponentRepository
import com.merxury.blocker.core.data.respository.generalrule.GeneralRuleRepository
import com.merxury.blocker.core.data.respository.userdata.UserDataRepository
import com.merxury.blocker.core.dispatchers.BlockerDispatchers.IO
import com.merxury.blocker.core.dispatchers.Dispatcher
import com.merxury.blocker.core.domain.InitializeDatabaseUseCase
import com.merxury.blocker.core.domain.InitializeRuleStorageUseCase
import com.merxury.blocker.core.domain.SearchGeneralRuleUseCase
import com.merxury.blocker.core.domain.applist.SearchAppListUseCase
import com.merxury.blocker.core.domain.model.InitializeState
import com.merxury.blocker.core.model.ComponentType.PROVIDER
import com.merxury.blocker.core.model.data.ComponentInfo
import com.merxury.blocker.core.model.data.ControllerType
import com.merxury.blocker.core.model.data.GeneralRule
import com.merxury.blocker.core.model.data.toAppItem
import com.merxury.blocker.core.result.Result
import com.merxury.blocker.core.ui.data.UiMessage
import com.merxury.blocker.core.ui.data.toErrorMessage
import com.merxury.blocker.core.utils.PackageInfoDataSource
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class EngineViewModel @Inject constructor(
    private val appRepository: AppRepository,
    private val componentRepository: ComponentRepository,
    private val generalRuleRepository: GeneralRuleRepository,
    private val initializeDatabase: InitializeDatabaseUseCase,
    private val initializeRuleStorage: InitializeRuleStorageUseCase,
    private val searchAppList: SearchAppListUseCase,
    private val searchGeneralRule: SearchGeneralRuleUseCase,
    private val packageInfoDataSource: PackageInfoDataSource,
    private val backupStore: EngineBackupStore,
    private val userDataRepository: UserDataRepository,
    @RootApiControl private val pmController: IController,
    @IfwControl private val ifwController: IController,
    @ShizukuControl private val shizukuController: IController,
    private val savedStateHandle: SavedStateHandle,
    @Dispatcher(IO) private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {
    private val _uiState = MutableStateFlow<EngineUiState>(EngineUiState.Loading())
    val uiState: StateFlow<EngineUiState> = _uiState.asStateFlow()

    private val _errorState = MutableStateFlow<UiMessage?>(null)
    val errorState = _errorState.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing = _isProcessing.asStateFlow()

    val selectedPackageName: StateFlow<String?> = savedStateHandle.getStateFlow(
        key = SELECTED_PACKAGE_KEY,
        initialValue = null,
    )

    private var catalog: List<EngineAppItem> = emptyList()
    private var installTokens: Map<String, String?> = emptyMap()
    private var loadJob: Job? = null
    private var selectionRefreshJob: Job? = null
    private var controlJob: Job? = null

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        Timber.e(throwable, "Engine operation failed")
        val message = throwable.toErrorMessage()
        _errorState.value = message
        if (_uiState.value is EngineUiState.Loading) {
            _uiState.value = EngineUiState.Error(message)
        }
        _isProcessing.value = false
    }

    init {
        loadData()
    }

    fun dismissError() = viewModelScope.launch {
        _errorState.emit(null)
    }

    fun selectApp(packageName: String?) {
        if (packageName != null && _isProcessing.value) return
        savedStateHandle[SELECTED_PACKAGE_KEY] = packageName
        selectionRefreshJob?.cancel()
        if (packageName == null || catalog.none { it.app.packageName == packageName }) return
        selectionRefreshJob = viewModelScope.launch(ioDispatcher + exceptionHandler) {
            // Components may have been changed from another Blocker screen while Engine was away.
            refreshPackage(packageName)
        }
    }

    fun loadData() {
        if (_isProcessing.value) return
        selectionRefreshJob?.cancel()
        loadJob?.cancel()
        loadJob = viewModelScope.launch(ioDispatcher + exceptionHandler) {
            _uiState.emit(EngineUiState.Loading(progress = null))

            // Keep the Engine page self-contained. The normal start destination performs the same
            // initialization, but Engine must still work correctly if it is reached after process
            // recreation before the Apps screen has rebuilt its caches.
            when (val result = appRepository.updateApplicationList()
                .first { it !is Result.Loading }) {
                is Result.Error -> throw result.exception
                is Result.Success -> Unit
                Result.Loading -> error("Unexpected loading result")
            }

            initializeDatabase()
                .first { it == InitializeState.Done }

            initializeRuleStorage()
                .first { it == InitializeState.Done }

            // Populate/refresh the rule database from Blocker's rule storage. The repository falls
            // back to bundled assets, so this remains usable without a network connection.
            when (val result = generalRuleRepository.updateGeneralRule()
                .first { it !is Result.Loading }) {
                is Result.Error -> throw result.exception
                is Result.Success -> Unit
                Result.Loading -> error("Unexpected loading result")
            }

            val installedApps = appRepository.getApplicationList().first()
            installTokens = installedApps.associate { app ->
                app.packageName to app.firstInstallTime?.toString()
            }
            backupStore.reconcileInstallations(installTokens)

            val visibleApps = searchAppList("").first()
            val visiblePackages = visibleApps.mapTo(mutableSetOf()) { it.packageName }
            // Always keep applications with Engine-owned restore records reachable, even if the
            // user later turns off "show system apps". Hiding such an app would otherwise make a
            // reversible Engine change impossible to reach from this screen.
            val restoreOnlyApps = installedApps.mapNotNull { installedApp ->
                if (
                    installedApp.packageName in visiblePackages ||
                    backupStore.managedComponentNames(installedApp.packageName).isEmpty()
                ) {
                    return@mapNotNull null
                }
                try {
                    installedApp.toAppItem(
                        packageInfo = packageInfoDataSource
                            .getApplicationComponents(installedApp.packageName),
                    )
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    Timber.w(
                        throwable,
                        "Unable to load package info for Engine restore row ${installedApp.packageName}",
                    )
                    installedApp.toAppItem()
                }
            }
            val apps = visibleApps + restoreOnlyApps
            var rules = searchGeneralRule().first()
            if (rules.isEmpty()) {
                // Defensive fallback for a just-created database.
                rules = generalRuleRepository.getGeneralRules().first()
            }

            val appByPackage = apps.associateBy { it.packageName }
            val matchedRulesByPackage = mutableMapOf<String, MutableList<EngineRuleItem>>()
            val total = rules.size.coerceAtLeast(1)

            rules.forEachIndexed { index, rule ->
                val matchedComponents = findMatchedComponents(rule)
                matchedComponents
                    .groupBy { it.packageName }
                    .forEach { (packageName, components) ->
                        if (packageName !in appByPackage) return@forEach
                        val uniqueComponents = components.distinctBy { it.name }
                        if (uniqueComponents.isEmpty()) return@forEach
                        matchedRulesByPackage
                            .getOrPut(packageName) { mutableListOf() }
                            .add(
                                EngineRuleItem(
                                    rule = rule,
                                    components = uniqueComponents,
                                    hasManagedChanges = backupStore
                                        .ownedComponentNames(packageName, rule.id)
                                        .isNotEmpty(),
                                ),
                            )
                    }
                _uiState.emit(
                    EngineUiState.Loading(
                        progress = (index + 1F) / total,
                    ),
                )
            }

            catalog = apps.mapNotNull { app ->
                val engines = matchedRulesByPackage[app.packageName]
                    ?.distinctBy { it.rule.id }
                    .orEmpty()
                val hasManagedChanges = backupStore
                    .managedComponentNames(app.packageName)
                    .isNotEmpty()
                if (engines.isEmpty() && !hasManagedChanges) {
                    null
                } else {
                    EngineAppItem(
                        app = app,
                        engines = engines.sortedWith(
                            compareByDescending<EngineRuleItem> { it.rule.safeToBlock == true }
                                .thenBy { it.rule.name.lowercase() },
                        ),
                        hasManagedChanges = hasManagedChanges,
                    )
                }
            }
            _uiState.emit(EngineUiState.Success(catalog))

            val selected = selectedPackageName.value
            if (selected != null && catalog.none { it.app.packageName == selected }) {
                selectApp(null)
            }
        }
    }

    fun disableEngine(packageName: String, ruleId: Int) {
        runControlOperation(packageName) {
            val engine = findEngine(packageName, ruleId) ?: return@runControlOperation
            disableEngineInternal(engine)
        }
    }

    fun restoreEngine(packageName: String, ruleId: Int) {
        runControlOperation(packageName) {
            val engine = findEngine(packageName, ruleId) ?: return@runControlOperation
            restoreEngineInternal(engine)
        }
    }

    /**
     * Used only when the rule is fully blocked but Engine has no restore record for it. It enables
     * externally blocked matched components only when they are not currently owned by another
     * Engine rule, preventing one overlapping rule from undoing another Engine rule's block.
     */
    fun forceEnableEngine(packageName: String, ruleId: Int) {
        runControlOperation(packageName) {
            val engine = findEngine(packageName, ruleId) ?: return@runControlOperation
            val current = componentRepository.getComponentList(packageName)
                .first()
                .associateBy { it.name }
            val toEnable = engine.components.mapNotNull { old -> current[old.name] }
                .filter { component ->
                    !component.enabled() && backupStore
                        .getOwners(packageName, component.name)
                        .isEmpty()
                }
            if (toEnable.isNotEmpty()) {
                val outcome = controlComponentsAndRead(packageName, toEnable, true)
                val stillBlocked = toEnable.filter { outcome.components[it.name]?.enabled() != true }
                var failure: Throwable? = if (stillBlocked.isNotEmpty()) outcome.failure else null
                if (stillBlocked.isNotEmpty()) {
                    Timber.w("Unable to enable ${stillBlocked.size} externally blocked Engine components")
                    failure = mergeFailure(
                        failure,
                        IllegalStateException(
                            "Unable to enable ${stillBlocked.size} externally blocked " +
                                "Engine component(s)",
                        ),
                    )
                }
                failure?.let { throw it }
            }
        }
    }

    fun disableRecommended(packageName: String) {
        runControlOperation(packageName) {
            val app = catalog.firstOrNull { it.app.packageName == packageName }
                ?: return@runControlOperation
            app.engines
                .filter { it.rule.safeToBlock == true && it.isEnabled }
                .forEach { disableEngineInternal(it) }
        }
    }

    fun restoreManagedChanges(packageName: String) {
        runControlOperation(packageName) {
            if (catalog.none { it.app.packageName == packageName }) {
                return@runControlOperation
            }
            restoreAllManagedComponents(packageName)
        }
    }

    private suspend fun restoreAllManagedComponents(packageName: String) {
        val managedNames = backupStore.managedComponentNames(packageName)
        if (managedNames.isEmpty()) return

        val current = componentRepository.getComponentList(packageName)
            .first()
            .associateBy { it.name }
        val requests = mutableListOf<ControllerRequest>()

        managedNames.forEach { componentName ->
            val component = current[componentName]
            val controllerType = backupStore.getControllerType(packageName, componentName)
            when {
                component == null -> {
                    // The component vanished after an app update; there is nothing left to restore.
                    backupStore.clearOwners(packageName, componentName)
                }

                controllerType != null && isControllerLayerEnabled(component, controllerType) -> {
                    // Engine's own controller layer was already restored elsewhere. A block from a
                    // different controller is not ours to remove.
                    backupStore.clearOwners(packageName, componentName)
                }

                controllerType == null && component.enabled() -> {
                    // Compatibility path for records created by Engine v1, which did not persist
                    // controller metadata.
                    backupStore.clearOwners(packageName, componentName)
                }

                else -> requests += ControllerRequest(component, controllerType)
            }
        }

        if (requests.isNotEmpty()) {
            val outcome = restoreRequestsAndRead(packageName, requests)
            requests.forEach { request ->
                val updated = outcome.components[request.component.name]
                if (updated != null && isRequestRestored(updated, request)) {
                    backupStore.clearOwners(packageName, request.component.name)
                }
            }
            val remaining = requests.count { request ->
                val updated = outcome.components[request.component.name]
                updated == null || !isRequestRestored(updated, request)
            }
            var failure: Throwable? = if (remaining > 0) outcome.failure else null
            if (remaining > 0) {
                Timber.w(
                    "Restore kept $remaining Engine backup records because their controller " +
                        "layers remain blocked",
                )
                failure = mergeFailure(
                    failure,
                    IllegalStateException("Unable to restore $remaining Engine component(s)"),
                )
            }
            failure?.let { throw it }
        }
    }

    private fun runControlOperation(
        packageName: String,
        action: suspend () -> Unit,
    ) {
        if (_isProcessing.value) return
        selectionRefreshJob?.cancel()
        controlJob?.cancel()
        controlJob = viewModelScope.launch(ioDispatcher + exceptionHandler) {
            _isProcessing.value = true
            try {
                var failure: Throwable? = null
                try {
                    action()
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    failure = throwable
                }

                try {
                    // Reflect partial successes as well as failures before the error dialog is shown.
                    refreshPackage(packageName)
                } catch (throwable: Throwable) {
                    if (throwable is CancellationException) throw throwable
                    failure = failure?.also { it.addSuppressed(throwable) } ?: throwable
                }

                failure?.let { throw it }
            } finally {
                _isProcessing.value = false
            }
        }
    }

    private suspend fun disableEngineInternal(engine: EngineRuleItem) {
        val packageName = engine.components.firstOrNull()?.packageName ?: return
        val ruleId = engine.rule.id
        val configuredControllerType = userDataRepository.userData.first().controllerType
        val current = componentRepository.getComponentList(packageName)
            .first()
            .associateBy { it.name }
        val requests = mutableListOf<ControllerRequest>()
        val stagedOwners = mutableSetOf<String>()

        engine.components.forEach { oldComponent ->
            val component = current[oldComponent.name] ?: return@forEach
            val owners = backupStore.getOwners(packageName, component.name)
            val storedControllerType = backupStore.getControllerType(packageName, component.name)
            val controllerType = storedControllerType
                ?: effectiveControllerType(configuredControllerType, component)

            when {
                owners.isNotEmpty() && isControllerLayerBlocked(component, controllerType) -> {
                    // The component is already blocked by Engine through an overlapping SDK rule.
                    // Only add this rule as another owner; the first rule's controller mode remains
                    // authoritative for restoration.
                    backupStore.addOwner(
                        packageName = packageName,
                        componentName = component.name,
                        ruleId = ruleId,
                        controllerType = controllerType,
                        installToken = installTokens[packageName],
                    )
                }

                component.enabled() -> {
                    // Persist ownership before the state-changing call. If Blocker is killed after
                    // Android accepts the disable but before we can re-read the state, the restore
                    // record still survives. A failed request removes only the owner staged here.
                    if (ruleId !in owners) {
                        backupStore.addOwner(
                            packageName = packageName,
                            componentName = component.name,
                            ruleId = ruleId,
                            controllerType = controllerType,
                            installToken = installTokens[packageName],
                        )
                        stagedOwners += component.name
                    } else if (storedControllerType == null) {
                        // Upgrade an Engine v1 record with the controller mode used by this retry.
                        backupStore.addOwner(
                            packageName = packageName,
                            componentName = component.name,
                            ruleId = ruleId,
                            controllerType = controllerType,
                            installToken = installTokens[packageName],
                        )
                    }
                    requests += ControllerRequest(component, controllerType)
                }

                else -> {
                    // The app is already blocked outside Engine and Engine's recorded layer is not
                    // responsible for the current block. Preserve the external/manual decision.
                }
            }
        }

        if (requests.isNotEmpty()) {
            val outcome = disableRequestsAndRead(packageName, requests)
            val failedRequests = requests.filter { request ->
                val updated = outcome.components[request.component.name]
                updated == null ||
                    request.controllerType == null ||
                    !isControllerLayerBlocked(updated, request.controllerType)
            }

            // Refreshed Android state is authoritative. A controller can throw/return failure even
            // though the requested state was applied, so surface the controller exception only for
            // requests that actually failed verification.
            var failure: Throwable? = if (failedRequests.isNotEmpty()) outcome.failure else null
            var rollbackFailedNames = emptySet<String>()
            if (failedRequests.isNotEmpty()) {
                // Every request in this branch started from a fully enabled component. A combined
                // controller can fail after changing only one layer, so roll the failed requests
                // back before dropping their newly staged ownership record. This prevents Engine
                // from leaving an untracked PM/IFW block behind after a partial failure.
                val rollback = restoreRequestsAndRead(packageName, failedRequests)
                val rollbackFailed = failedRequests.filter { request ->
                    val updated = rollback.components[request.component.name]
                    updated == null || !isRequestRestored(updated, request)
                }
                rollbackFailedNames = rollbackFailed.mapTo(mutableSetOf()) { it.component.name }
                // Ignore a rollback controller exception when Android state proves every rollback
                // succeeded. If any rollback is still blocked, keep both the exception and a clear
                // verification error for the user.
                if (rollbackFailed.isNotEmpty()) {
                    failure = mergeFailure(failure, rollback.failure)
                    failure = mergeFailure(
                        failure,
                        IllegalStateException(
                            "Failed to roll back ${rollbackFailed.size} partially disabled " +
                                "Engine component(s)",
                        ),
                    )
                }
            }

            failedRequests.forEach { request ->
                // If rollback itself failed, retain the staged owner/controller record. The block
                // may still exist after this process dies, and the restore record is the only safe
                // way for a later Engine session to retry restoration.
                if (
                    request.component.name in stagedOwners &&
                    request.component.name !in rollbackFailedNames
                ) {
                    backupStore.removeOwner(packageName, request.component.name, ruleId)
                }
            }
            if (failedRequests.isNotEmpty()) {
                Timber.w(
                    "Engine did not disable ${failedRequests.size} requested controller layers; " +
                        "rollback was attempted and unresolved restore records were retained",
                )
                failure = mergeFailure(
                    failure,
                    IllegalStateException(
                        "Unable to disable ${failedRequests.size} Engine component(s)",
                    ),
                )
            }
            failure?.let { throw it }
        }
    }

    private suspend fun restoreEngineInternal(engine: EngineRuleItem) {
        val packageName = engine.components.firstOrNull()?.packageName ?: return
        val ruleId = engine.rule.id
        val ownedNames = backupStore.ownedComponentNames(packageName, ruleId)
        if (ownedNames.isEmpty()) return

        val current = componentRepository.getComponentList(packageName)
            .first()
            .associateBy { it.name }
        val requests = mutableListOf<ControllerRequest>()

        ownedNames.forEach { componentName ->
            val owners = backupStore.getOwners(packageName, componentName)
            val component = current[componentName]
            val controllerType = backupStore.getControllerType(packageName, componentName)
            when {
                ruleId !in owners -> Unit

                owners.size > 1 -> {
                    // Another Engine rule still owns the same Engine-created block.
                    backupStore.removeOwner(packageName, componentName, ruleId)
                }

                component == null -> {
                    backupStore.removeOwner(packageName, componentName, ruleId)
                }

                controllerType != null && isControllerLayerEnabled(component, controllerType) -> {
                    // Engine's layer is already enabled. If another layer is blocked, preserve it.
                    backupStore.removeOwner(packageName, componentName, ruleId)
                }

                controllerType == null && component.enabled() -> {
                    // Compatibility path for Engine v1 restore records.
                    backupStore.removeOwner(packageName, componentName, ruleId)
                }

                else -> requests += ControllerRequest(component, controllerType)
            }
        }

        if (requests.isNotEmpty()) {
            val outcome = restoreRequestsAndRead(packageName, requests)
            requests.forEach { request ->
                val updated = outcome.components[request.component.name]
                if (updated != null && isRequestRestored(updated, request)) {
                    backupStore.removeOwner(packageName, request.component.name, ruleId)
                }
            }
            val remaining = requests.count { request ->
                val updated = outcome.components[request.component.name]
                updated == null || !isRequestRestored(updated, request)
            }
            var failure: Throwable? = if (remaining > 0) outcome.failure else null
            if (remaining > 0) {
                Timber.w(
                    "Engine kept $remaining restore owners because their controller layers " +
                        "remain blocked",
                )
                failure = mergeFailure(
                    failure,
                    IllegalStateException("Unable to restore $remaining Engine component(s)"),
                )
            }
            failure?.let { throw it }
        }
    }

    /**
     * Used by the explicit "force enable" path. It intentionally follows Blocker's normal
     * repository controller behavior because the user has confirmed that externally-created
     * blocks may be overridden.
     */
    private suspend fun controlComponentsAndRead(
        packageName: String,
        components: List<ComponentInfo>,
        newState: Boolean,
    ): ControlOutcome {
        var failure: Throwable? = null
        components.forEach { component ->
            try {
                componentRepository.controlComponent(
                    component = component,
                    newState = newState,
                ).first()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                failure = failure?.also { it.addSuppressed(throwable) } ?: throwable
            }
        }

        val current = refreshAndReadComponents(packageName)
        return ControlOutcome(components = current, failure = failure)
    }

    /**
     * Applies Engine-owned disables with an explicit controller mode. The explicit mode prevents a
     * settings change during an operation from moving the block to a different controller layer.
     */
    private suspend fun disableRequestsAndRead(
        packageName: String,
        requests: List<ControllerRequest>,
    ): ControlOutcome {
        var failure: Throwable? = null
        requests.forEach { request ->
            val controllerType = request.controllerType ?: return@forEach
            try {
                componentRepository.controlComponent(
                    component = request.component,
                    newState = false,
                    controllerType = controllerType,
                ).first()
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                failure = failure?.also { it.addSuppressed(throwable) } ?: throwable
            }
        }
        val current = refreshAndReadComponents(packageName)
        return ControlOutcome(components = current, failure = failure)
    }

    /**
     * Restores only the controller layer that Engine originally changed. This deliberately bypasses
     * ComponentRepository's normal PM/IFW cross-unblocking behavior for v2 records, otherwise a
     * manual block created on another controller after Engine ran could be removed accidentally.
     * Records created by Engine v1 have no controller metadata and retain the old repository-based
     * fallback behavior.
     */
    private suspend fun restoreRequestsAndRead(
        packageName: String,
        requests: List<ControllerRequest>,
    ): ControlOutcome {
        var failure: Throwable? = null
        requests.forEach { request ->
            try {
                request.controllerType?.let { controllerType ->
                    restoreControllerLayer(request.component, controllerType)
                } ?: componentRepository.controlComponent(
                    component = request.component,
                    newState = true,
                ).first()
                // Controller return values are advisory here. The refreshed PM/IFW state below is
                // the source of truth and avoids reporting a false failure when a controller command
                // returned false even though Android applied the requested state.
            } catch (throwable: Throwable) {
                if (throwable is CancellationException) throw throwable
                failure = failure?.also { it.addSuppressed(throwable) } ?: throwable
            }
        }
        val current = refreshAndReadComponents(packageName)
        return ControlOutcome(components = current, failure = failure)
    }

    private suspend fun restoreControllerLayer(
        component: ComponentInfo,
        controllerType: ControllerType,
    ): Boolean = when (controllerType) {
        ControllerType.PM -> pmController.enable(component)
        ControllerType.IFW -> ifwController.enable(component)
        ControllerType.SHIZUKU -> {
            // Shizuku and PM both change the PackageManager layer. Prefer the original controller,
            // but a rooted device can still recover the state if Shizuku authorization vanished.
            shizukuController.enable(component) || pmController.enable(component)
        }
        ControllerType.IFW_PLUS_PM -> {
            val ifwResult = ifwController.enable(component)
            val pmResult = pmController.enable(component)
            ifwResult && pmResult
        }
    }

    private suspend fun refreshAndReadComponents(packageName: String): Map<String, ComponentInfo> {
        when (val result = componentRepository.updateComponentList(packageName)
            .first { it !is Result.Loading }) {
            is Result.Error -> throw result.exception
            is Result.Success -> Unit
            Result.Loading -> error("Unexpected loading result")
        }
        return componentRepository.getComponentList(packageName)
            .first()
            .associateBy { it.name }
    }

    private fun effectiveControllerType(
        configuredControllerType: ControllerType,
        component: ComponentInfo,
    ): ControllerType = if (
        component.type == PROVIDER &&
        configuredControllerType in setOf(ControllerType.IFW, ControllerType.IFW_PLUS_PM)
    ) {
        // IFW cannot control providers. Blocker's IFW mode already delegates providers to PM; do
        // the same for combined mode so a failed IFW half cannot leave a PM-only block without a
        // valid Engine restore record.
        ControllerType.PM
    } else {
        configuredControllerType
    }

    private fun isControllerLayerBlocked(
        component: ComponentInfo,
        controllerType: ControllerType,
    ): Boolean = when (controllerType) {
        ControllerType.PM,
        ControllerType.SHIZUKU -> component.pmBlocked
        ControllerType.IFW -> component.ifwBlocked
        ControllerType.IFW_PLUS_PM -> component.pmBlocked && component.ifwBlocked
    }

    private fun isControllerLayerEnabled(
        component: ComponentInfo,
        controllerType: ControllerType,
    ): Boolean = when (controllerType) {
        ControllerType.PM,
        ControllerType.SHIZUKU -> !component.pmBlocked
        ControllerType.IFW -> !component.ifwBlocked
        ControllerType.IFW_PLUS_PM -> !component.pmBlocked && !component.ifwBlocked
    }

    private fun isRequestRestored(
        component: ComponentInfo,
        request: ControllerRequest,
    ): Boolean = request.controllerType?.let { isControllerLayerEnabled(component, it) }
        ?: component.enabled()

    private suspend fun refreshPackage(packageName: String) {
        when (val result = componentRepository.updateComponentList(packageName)
            .first { it !is Result.Loading }) {
            is Result.Error -> throw result.exception
            is Result.Success -> Unit
            Result.Loading -> error("Unexpected loading result")
        }
        val currentByName = componentRepository.getComponentList(packageName)
            .first()
            .associateBy { it.name }

        catalog = catalog.mapNotNull { appItem ->
            if (appItem.app.packageName != packageName) return@mapNotNull appItem
            val engines = appItem.engines.mapNotNull { engine ->
                val components = engine.components.mapNotNull { currentByName[it.name] }
                if (components.isEmpty()) {
                    null
                } else {
                    engine.copy(
                        components = components,
                        hasManagedChanges = backupStore
                            .ownedComponentNames(packageName, engine.rule.id)
                            .isNotEmpty(),
                    )
                }
            }
            val hasManagedChanges = backupStore
                .managedComponentNames(packageName)
                .isNotEmpty()
            if (engines.isEmpty() && !hasManagedChanges) {
                null
            } else {
                appItem.copy(
                    engines = engines,
                    hasManagedChanges = hasManagedChanges,
                )
            }
        }
        if (
            selectedPackageName.value == packageName &&
            catalog.none { it.app.packageName == packageName }
        ) {
            selectApp(null)
        }
        _uiState.emit(EngineUiState.Success(catalog))
    }

    private fun mergeFailure(current: Throwable?, next: Throwable?): Throwable? = when {
        next == null -> current
        current == null -> next
        current === next -> current
        else -> current.also { it.addSuppressed(next) }
    }

    private suspend fun findMatchedComponents(rule: GeneralRule): List<ComponentInfo> {
        val result = LinkedHashMap<String, ComponentInfo>()
        rule.searchKeyword.forEach { keyword ->
            componentRepository.searchComponent(keyword)
                .first()
                .forEach { component ->
                    val key = "${component.packageName}/${component.name}"
                    result.putIfAbsent(key, component)
                }
        }
        return result.values.toList()
    }

    private fun findEngine(packageName: String, ruleId: Int): EngineRuleItem? = catalog
        .firstOrNull { it.app.packageName == packageName }
        ?.engines
        ?.firstOrNull { it.rule.id == ruleId }

    private data class ControllerRequest(
        val component: ComponentInfo,
        val controllerType: ControllerType?,
    )

    private data class ControlOutcome(
        val components: Map<String, ComponentInfo>,
        val failure: Throwable?,
    )

    private companion object {
        const val SELECTED_PACKAGE_KEY = "engineSelectedPackage"
    }
}
