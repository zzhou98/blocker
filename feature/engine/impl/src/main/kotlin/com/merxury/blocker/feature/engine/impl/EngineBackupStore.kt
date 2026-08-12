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

import android.content.Context
import android.content.SharedPreferences
import com.merxury.blocker.core.model.data.ControllerType
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records only component changes owned by the Engine screen.
 *
 * A component can be matched by several SDK rules, therefore ownership is reference-counted by
 * rule id. The controller type that performed the first Engine block is stored separately so a
 * later restore can undo only that controller layer instead of blindly enabling every layer.
 * Components already blocked before Engine touches them are deliberately not recorded.
 *
 * Synchronous [SharedPreferences.Editor.commit] is intentional: component state survives the
 * Blocker process, so the restore record must be durable before/alongside a state-changing call.
 */
@Singleton
class EngineBackupStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val preferences = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @Synchronized
    fun getOwners(packageName: String, componentName: String): Set<Int> = preferences
        .getStringSet(componentKey(packageName, componentName), emptySet())
        .orEmpty()
        .mapNotNull(String::toIntOrNull)
        .toSet()

    @Synchronized
    fun getControllerType(packageName: String, componentName: String): ControllerType? = preferences
        .getString(controllerKey(packageName, componentName), null)
        ?.let { stored -> runCatching { ControllerType.valueOf(stored) }.getOrNull() }

    /**
     * Adds [ruleId] as an owner. [controllerType] is written only when the component has no stored
     * controller metadata yet, so overlapping rules never replace the mode that created the block.
     */
    @Synchronized
    fun addOwner(
        packageName: String,
        componentName: String,
        ruleId: Int,
        controllerType: ControllerType? = null,
        installToken: String? = null,
    ) {
        val owners = getOwners(packageName, componentName).toMutableSet()
        val ownerAdded = owners.add(ruleId)
        val shouldStoreController = controllerType != null &&
            getControllerType(packageName, componentName) == null
        val shouldStoreInstall = installToken != null &&
            preferences.getString(installKey(packageName), null) != installToken
        if (!ownerAdded && !shouldStoreController && !shouldStoreInstall) return

        val editor = preferences.edit()
        if (ownerAdded) {
            editor.putStringSet(
                componentKey(packageName, componentName),
                owners.map(Int::toString).toSet(),
            )
        }
        if (shouldStoreController) {
            editor.putString(controllerKey(packageName, componentName), controllerType.name)
        }
        if (shouldStoreInstall) {
            editor.putString(installKey(packageName), installToken)
        }
        editor.commitOrThrow()
    }

    @Synchronized
    fun removeOwner(packageName: String, componentName: String, ruleId: Int) {
        val key = componentKey(packageName, componentName)
        val owners = getOwners(packageName, componentName).toMutableSet()
        if (!owners.remove(ruleId)) return
        val editor = preferences.edit()
        if (owners.isEmpty()) {
            editor.remove(key)
            editor.remove(controllerKey(packageName, componentName))
        } else {
            editor.putStringSet(key, owners.map(Int::toString).toSet())
        }
        editor.commitOrThrow()
    }

    @Synchronized
    fun ownedComponentNames(packageName: String, ruleId: Int): Set<String> {
        val prefix = componentPrefix(packageName)
        return preferences.all.entries
            .asSequence()
            .filter { (key, _) -> key.startsWith(prefix) }
            .mapNotNull { (key, value) ->
                @Suppress("UNCHECKED_CAST")
                val owners = (value as? Set<String>).orEmpty()
                if (ruleId.toString() in owners) key.removePrefix(prefix) else null
            }
            .toSet()
    }

    @Synchronized
    fun managedComponentNames(packageName: String): Set<String> {
        val prefix = componentPrefix(packageName)
        return preferences.all.keys
            .asSequence()
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toSet()
    }

    @Synchronized
    fun clearOwners(packageName: String, componentName: String) {
        val editor = preferences.edit()
            .remove(componentKey(packageName, componentName))
            .remove(controllerKey(packageName, componentName))
        editor.commitOrThrow()
    }

    /**
     * Removes stale records for uninstalled applications and for a package that has been
     * uninstalled/reinstalled since Engine created its backup. A normal in-place app update keeps
     * the same first-install token, therefore its restore records survive updates.
     */
    @Synchronized
    fun reconcileInstallations(installedApps: Map<String, String?>) {
        val managedPackages = preferences.all.keys
            .asSequence()
            .filter { it.startsWith(COMPONENT_PREFIX) }
            .map { key ->
                key.removePrefix(COMPONENT_PREFIX).substringBefore(KEY_SEPARATOR)
            }
            .toSet()

        managedPackages.forEach { packageName ->
            if (packageName !in installedApps) {
                clearPackage(packageName)
                return@forEach
            }
            val currentToken = installedApps[packageName] ?: return@forEach
            val storedToken = preferences.getString(installKey(packageName), null)
            when {
                storedToken == null -> {
                    // Adopt the current installation for records created by an older Engine build.
                    preferences.edit()
                        .putString(installKey(packageName), currentToken)
                        .commitOrThrow()
                }

                storedToken != currentToken -> clearPackage(packageName)
            }
        }

        // Installation/controller metadata without a component ownership record is housekeeping
        // data only and can be removed safely.
        val activeManagedPackages = preferences.all.keys
            .asSequence()
            .filter { it.startsWith(COMPONENT_PREFIX) }
            .map { key -> key.removePrefix(COMPONENT_PREFIX).substringBefore(KEY_SEPARATOR) }
            .toSet()

        preferences.all.keys
            .filter { it.startsWith(INSTALL_PREFIX) }
            .forEach { key ->
                val packageName = key.removePrefix(INSTALL_PREFIX)
                if (packageName !in activeManagedPackages || packageName !in installedApps) {
                    preferences.edit().remove(key).commitOrThrow()
                }
            }

        preferences.all.keys
            .filter { it.startsWith(CONTROLLER_PREFIX) }
            .forEach { key ->
                val suffix = key.removePrefix(CONTROLLER_PREFIX)
                val packageName = suffix.substringBefore(KEY_SEPARATOR)
                val componentName = suffix.substringAfter(KEY_SEPARATOR, missingDelimiterValue = "")
                if (
                    componentName.isEmpty() ||
                    getOwners(packageName, componentName).isEmpty()
                ) {
                    preferences.edit().remove(key).commitOrThrow()
                }
            }
    }

    @Synchronized
    private fun clearPackage(packageName: String) {
        val componentPrefix = componentPrefix(packageName)
        val controllerPrefix = controllerPrefix(packageName)
        val editor = preferences.edit()
        preferences.all.keys
            .filter { it.startsWith(componentPrefix) || it.startsWith(controllerPrefix) }
            .forEach(editor::remove)
        editor.remove(installKey(packageName))
        editor.commitOrThrow()
    }

    private fun componentKey(packageName: String, componentName: String): String =
        componentPrefix(packageName) + componentName

    private fun componentPrefix(packageName: String): String =
        "$COMPONENT_PREFIX$packageName$KEY_SEPARATOR"

    private fun controllerKey(packageName: String, componentName: String): String =
        controllerPrefix(packageName) + componentName

    private fun controllerPrefix(packageName: String): String =
        "$CONTROLLER_PREFIX$packageName$KEY_SEPARATOR"

    private fun installKey(packageName: String): String = "$INSTALL_PREFIX$packageName"

    private fun SharedPreferences.Editor.commitOrThrow() {
        check(commit()) { "Failed to persist Engine restore metadata" }
    }

    private companion object {
        const val PREF_NAME = "blocker_engine_backup"
        const val COMPONENT_PREFIX = "component|"
        const val CONTROLLER_PREFIX = "controller|"
        const val INSTALL_PREFIX = "install|"
        const val KEY_SEPARATOR = "|"
    }
}
