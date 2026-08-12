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

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.merxury.blocker.core.designsystem.component.BlockerAppTopBarMenu
import com.merxury.blocker.core.designsystem.component.BlockerBodyLargeText
import com.merxury.blocker.core.designsystem.component.BlockerBodyMediumText
import com.merxury.blocker.core.designsystem.component.BlockerErrorAlertDialog
import com.merxury.blocker.core.designsystem.component.BlockerLoadingWheel
import com.merxury.blocker.core.designsystem.component.BlockerTopAppBar
import com.merxury.blocker.core.designsystem.component.BlockerTopAppBarWithProgress
import com.merxury.blocker.core.designsystem.component.BlockerWarningAlertDialog
import com.merxury.blocker.core.designsystem.component.DropDownMenuItem
import com.merxury.blocker.core.designsystem.component.PreviewThemes
import com.merxury.blocker.core.designsystem.component.scrollbar.DraggableScrollbar
import com.merxury.blocker.core.designsystem.component.scrollbar.rememberDraggableScroller
import com.merxury.blocker.core.designsystem.component.scrollbar.scrollbarState
import com.merxury.blocker.core.designsystem.icon.BlockerIcons
import com.merxury.blocker.core.designsystem.theme.BlockerTheme
import com.merxury.blocker.core.model.data.AppItem
import com.merxury.blocker.core.model.data.ComponentInfo
import com.merxury.blocker.core.model.data.GeneralRule
import com.merxury.blocker.core.ui.R.string as uiString
import com.merxury.blocker.core.ui.TrackScreenViewEvent
import com.merxury.blocker.core.ui.applist.AppIcon
import com.merxury.blocker.core.ui.rule.RuleItemHeader
import com.merxury.blocker.core.ui.screen.EmptyScreen
import com.merxury.blocker.core.ui.screen.ErrorScreen
import com.merxury.blocker.core.ui.screen.LoadingScreen
import com.merxury.blocker.feature.engine.impl.EngineUiState.Error
import com.merxury.blocker.feature.engine.impl.EngineUiState.Loading
import com.merxury.blocker.feature.engine.impl.EngineUiState.Success
import com.merxury.blocker.feature.engine.impl.R.string

@Composable
fun EngineScreen(
    modifier: Modifier = Modifier,
    viewModel: EngineViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val errorState by viewModel.errorState.collectAsStateWithLifecycle()
    val isProcessing by viewModel.isProcessing.collectAsStateWithLifecycle()
    val selectedPackageName by viewModel.selectedPackageName.collectAsStateWithLifecycle()

    val selectedApp = (uiState as? Success)
        ?.apps
        ?.firstOrNull { it.app.packageName == selectedPackageName }

    BackHandler(enabled = selectedApp != null) {
        viewModel.selectApp(null)
    }

    EngineScreen(
        uiState = uiState,
        selectedApp = selectedApp,
        isProcessing = isProcessing,
        modifier = modifier,
        onAppClick = viewModel::selectApp,
        onBackClick = { viewModel.selectApp(null) },
        onDisableEngine = viewModel::disableEngine,
        onRestoreEngine = viewModel::restoreEngine,
        onForceEnableEngine = viewModel::forceEnableEngine,
        onDisableRecommended = viewModel::disableRecommended,
        onRestoreManagedChanges = viewModel::restoreManagedChanges,
    )

    if (errorState != null) {
        BlockerErrorAlertDialog(
            title = errorState?.title.orEmpty(),
            text = errorState?.content.orEmpty(),
            onDismissRequest = viewModel::dismissError,
        )
    }
}

@Composable
fun EngineScreen(
    uiState: EngineUiState,
    selectedApp: EngineAppItem?,
    isProcessing: Boolean,
    modifier: Modifier = Modifier,
    onAppClick: (String?) -> Unit = {},
    onBackClick: () -> Unit = {},
    onDisableEngine: (String, Int) -> Unit = { _, _ -> },
    onRestoreEngine: (String, Int) -> Unit = { _, _ -> },
    onForceEnableEngine: (String, Int) -> Unit = { _, _ -> },
    onDisableRecommended: (String) -> Unit = {},
    onRestoreManagedChanges: (String) -> Unit = {},
) {
    val selectedPackageName = selectedApp?.app?.packageName
    var unsafeRuleId by rememberSaveable(selectedPackageName) { mutableStateOf<Int?>(null) }
    var forceEnableRuleId by rememberSaveable(selectedPackageName) { mutableStateOf<Int?>(null) }
    var showDisableRecommendedDialog by rememberSaveable(selectedPackageName) { mutableStateOf(false) }
    var showRestoreDialog by rememberSaveable(selectedPackageName) { mutableStateOf(false) }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (selectedApp == null) {
            val progress = (uiState as? Loading)?.progress
            BlockerTopAppBarWithProgress(
                title = stringResource(id = string.feature_engine_impl_title),
                progress = progress,
            )
        } else {
            BlockerTopAppBar(
                title = selectedApp.app.label,
                hasNavigationIcon = true,
                onNavigationClick = onBackClick,
                actions = {
                    if (isProcessing) {
                        BlockerLoadingWheel(modifier = Modifier.size(36.dp))
                    } else {
                        EngineMoreActionMenu(
                            canDisableRecommended = selectedApp.enabledRecommendedCount > 0,
                            canRestore = selectedApp.hasManagedChanges,
                            onDisableRecommended = { showDisableRecommendedDialog = true },
                            onRestore = { showRestoreDialog = true },
                        )
                    }
                },
            )
        }

        when (uiState) {
            is Loading -> LoadingScreen()
            is Error -> ErrorScreen(uiState.error)
            is Success -> {
                if (selectedApp == null) {
                    if (uiState.apps.isEmpty()) {
                        EmptyScreen(textRes = string.feature_engine_impl_no_apps)
                    } else {
                        EngineAppList(
                            apps = uiState.apps,
                            enabled = !isProcessing,
                            onAppClick = { onAppClick(it) },
                        )
                    }
                } else {
                    if (selectedApp.engines.isEmpty()) {
                        EmptyScreen(textRes = string.feature_engine_impl_no_rules)
                    } else {
                        EngineRuleList(
                            appItem = selectedApp,
                            isProcessing = isProcessing,
                            onToggle = { engine, enable ->
                                val packageName = selectedApp.app.packageName
                                when {
                                    enable && engine.hasManagedChanges -> {
                                        onRestoreEngine(packageName, engine.rule.id)
                                    }

                                    enable -> {
                                        forceEnableRuleId = engine.rule.id
                                    }

                                    engine.rule.safeToBlock == true -> {
                                        onDisableEngine(packageName, engine.rule.id)
                                    }

                                    else -> {
                                        unsafeRuleId = engine.rule.id
                                    }
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    val unsafeRule = selectedApp?.engines?.firstOrNull { it.rule.id == unsafeRuleId }
    if (unsafeRule != null) {
        BlockerWarningAlertDialog(
            title = unsafeRule.rule.name,
            text = stringResource(id = string.feature_engine_impl_unsafe_confirm),
            onDismissRequest = { unsafeRuleId = null },
            onConfirmRequest = {
                unsafeRuleId = null
                onDisableEngine(selectedApp.app.packageName, unsafeRule.rule.id)
            },
        )
    }

    val forceEnableRule = selectedApp?.engines?.firstOrNull { it.rule.id == forceEnableRuleId }
    if (forceEnableRule != null) {
        BlockerWarningAlertDialog(
            title = forceEnableRule.rule.name,
            text = stringResource(id = string.feature_engine_impl_force_enable_confirm),
            onDismissRequest = { forceEnableRuleId = null },
            onConfirmRequest = {
                forceEnableRuleId = null
                onForceEnableEngine(selectedApp.app.packageName, forceEnableRule.rule.id)
            },
        )
    }

    if (showDisableRecommendedDialog && selectedApp != null) {
        BlockerWarningAlertDialog(
            title = stringResource(id = string.feature_engine_impl_disable_recommended),
            text = stringResource(
                id = string.feature_engine_impl_disable_recommended_confirm,
                selectedApp.app.label,
                selectedApp.enabledRecommendedCount,
            ),
            onDismissRequest = { showDisableRecommendedDialog = false },
            onConfirmRequest = {
                showDisableRecommendedDialog = false
                onDisableRecommended(selectedApp.app.packageName)
            },
        )
    }

    if (showRestoreDialog && selectedApp != null) {
        BlockerWarningAlertDialog(
            title = stringResource(id = string.feature_engine_impl_restore_changes),
            text = stringResource(id = string.feature_engine_impl_restore_confirm, selectedApp.app.label),
            onDismissRequest = { showRestoreDialog = false },
            onConfirmRequest = {
                showRestoreDialog = false
                onRestoreManagedChanges(selectedApp.app.packageName)
            },
        )
    }

    TrackScreenViewEvent(screenName = "EngineScreen")
}

@Composable
private fun EngineMoreActionMenu(
    canDisableRecommended: Boolean,
    canRestore: Boolean,
    onDisableRecommended: () -> Unit,
    onRestore: () -> Unit,
) {
    val items = buildList {
        if (canDisableRecommended) {
            add(DropDownMenuItem(string.feature_engine_impl_disable_recommended, onDisableRecommended))
        }
        if (canRestore) {
            add(DropDownMenuItem(string.feature_engine_impl_restore_changes, onRestore))
        }
    }
    if (items.isEmpty()) return
    BlockerAppTopBarMenu(
        menuIcon = BlockerIcons.MoreVert,
        menuIconDesc = uiString.core_ui_more_menu,
        menuList = items,
    )
}

@Composable
private fun EngineAppList(
    apps: List<EngineAppItem>,
    enabled: Boolean,
    onAppClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val scrollbarState = listState.scrollbarState(itemsAvailable = apps.size)
    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.testTag("engine:appList"),
        ) {
            items(apps, key = { it.app.packageName }) { item ->
                EngineAppListItem(
                    item = item,
                    enabled = enabled,
                    onClick = onAppClick,
                )
            }
            item {
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
            }
        }
        listState.DraggableScrollbar(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 2.dp)
                .align(Alignment.CenterEnd)
                .testTag("engine:appScrollbar"),
            state = scrollbarState,
            orientation = Orientation.Vertical,
            onThumbMove = listState.rememberDraggableScroller(itemsAvailable = apps.size),
        )
    }
}

@Composable
private fun EngineAppListItem(
    item: EngineAppItem,
    enabled: Boolean,
    onClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .clickable(enabled = enabled) { onClick(item.app.packageName) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AppIcon(item.app.packageInfo, Modifier.size(48.dp))
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1F)) {
            BlockerBodyLargeText(text = item.app.label)
            BlockerBodyMediumText(
                text = if (item.engines.isEmpty() && item.hasManagedChanges) {
                    stringResource(id = string.feature_engine_impl_app_summary_restore_pending)
                } else {
                    stringResource(
                        id = string.feature_engine_impl_app_summary,
                        item.engines.size,
                        item.recommendedCount,
                    )
                },
            )
        }
        if (item.engines.isNotEmpty()) {
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = item.engines.size.toString(),
                textAlign = TextAlign.Center,
                fontSize = 12.sp,
                modifier = Modifier
                    .size(24.dp)
                    .background(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = CircleShape,
                    ),
            )
        }
    }
}

@Composable
private fun EngineRuleList(
    appItem: EngineAppItem,
    isProcessing: Boolean,
    onToggle: (EngineRuleItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val recommended = appItem.engines.filter { it.rule.safeToBlock == true }
    val caution = appItem.engines.filterNot { it.rule.safeToBlock == true }
    val listState = rememberLazyListState()
    val totalItems = appItem.engines.size
    val scrollbarState = listState.scrollbarState(itemsAvailable = totalItems)

    Box(modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.testTag("engine:ruleList"),
        ) {
            if (recommended.isNotEmpty()) {
                item {
                    RuleItemHeader(title = stringResource(id = string.feature_engine_impl_recommended))
                }
                items(recommended, key = { it.rule.id }) { engine ->
                    EngineRuleItemRow(
                        engine = engine,
                        isProcessing = isProcessing,
                        onToggle = onToggle,
                    )
                }
            }
            if (recommended.isNotEmpty() && caution.isNotEmpty()) {
                item {
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 8.dp))
                }
            }
            if (caution.isNotEmpty()) {
                item {
                    RuleItemHeader(title = stringResource(id = string.feature_engine_impl_caution))
                }
                items(caution, key = { it.rule.id }) { engine ->
                    EngineRuleItemRow(
                        engine = engine,
                        isProcessing = isProcessing,
                        onToggle = onToggle,
                    )
                }
            }
            item {
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
            }
        }
        listState.DraggableScrollbar(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 2.dp)
                .align(Alignment.CenterEnd)
                .testTag("engine:ruleScrollbar"),
            state = scrollbarState,
            orientation = Orientation.Vertical,
            onThumbMove = listState.rememberDraggableScroller(itemsAvailable = totalItems),
        )
    }
}

@Composable
private fun EngineRuleItemRow(
    engine: EngineRuleItem,
    isProcessing: Boolean,
    onToggle: (EngineRuleItem, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        AsyncImage(
            modifier = Modifier.size(48.dp),
            model = ImageRequest.Builder(LocalContext.current)
                .data(engine.rule.iconUrl)
                .error(BlockerIcons.Android)
                .placeholder(BlockerIcons.Android)
                .crossfade(true)
                .build(),
            contentDescription = stringResource(id = uiString.core_ui_rule_icon_description),
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1F)) {
            BlockerBodyLargeText(text = engine.rule.name)
            engine.rule.company?.takeIf { it.isNotBlank() }?.let {
                BlockerBodyMediumText(text = it)
            }
            val safetyText = when (engine.rule.safeToBlock) {
                true -> stringResource(id = string.feature_engine_impl_safe)
                false -> stringResource(id = string.feature_engine_impl_not_recommended)
                null -> stringResource(id = string.feature_engine_impl_unknown)
            }
            BlockerBodyMediumText(
                text = stringResource(
                    id = string.feature_engine_impl_component_status,
                    safetyText,
                    engine.components.size,
                    engine.blockedCount,
                ),
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = engine.isEnabled,
            onCheckedChange = if (isProcessing) {
                null
            } else {
                { onToggle(engine, it) }
            },
        )
    }
}

@PreviewThemes
@Composable
private fun EngineAppListPreview() {
    BlockerTheme {
        Surface {
            val app = AppItem(label = "Example", packageName = "com.example")
            val rule = GeneralRule(id = 1, name = "Analytics", company = "Example", safeToBlock = true)
            val component = ComponentInfo(
                packageName = "com.example",
                name = "com.example.AnalyticsService",
                type = com.merxury.blocker.core.model.ComponentType.SERVICE,
            )
            EngineScreen(
                uiState = Success(
                    listOf(
                        EngineAppItem(
                            app = app,
                            engines = listOf(EngineRuleItem(rule, listOf(component))),
                        ),
                    ),
                ),
                selectedApp = null,
                isProcessing = false,
            )
        }
    }
}

@Preview
@Composable
private fun EngineDetailPreview() {
    BlockerTheme {
        Surface {
            val app = AppItem(label = "Example", packageName = "com.example")
            val rule = GeneralRule(id = 1, name = "Analytics", company = "Example", safeToBlock = true)
            val component = ComponentInfo(
                packageName = "com.example",
                name = "com.example.AnalyticsService",
                type = com.merxury.blocker.core.model.ComponentType.SERVICE,
            )
            val engineApp = EngineAppItem(
                app = app,
                engines = listOf(EngineRuleItem(rule, listOf(component))),
            )
            EngineScreen(
                uiState = Success(listOf(engineApp)),
                selectedApp = engineApp,
                isProcessing = false,
            )
        }
    }
}
