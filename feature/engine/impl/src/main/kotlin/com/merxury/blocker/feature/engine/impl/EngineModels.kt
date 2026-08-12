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

import com.merxury.blocker.core.model.data.AppItem
import com.merxury.blocker.core.model.data.ComponentInfo
import com.merxury.blocker.core.model.data.GeneralRule
import com.merxury.blocker.core.model.ComponentType
import com.merxury.blocker.core.ui.data.UiMessage

enum class EngineRiskLevel {
    SAFE,
    CAUTION,
    HIGH_RISK,
}

data class EngineAppItem(
    val app: AppItem,
    val engines: List<EngineRuleItem>,
    val hasManagedChanges: Boolean = false,
) {
    val recommendedCount: Int
        get() = engines.count { it.rule.safeToBlock == true }

    val enabledRecommendedCount: Int
        get() = engines.count { it.riskLevel == EngineRiskLevel.SAFE && it.isEnabled }

    val componentCount: Int
        get() = engines.sumOf { it.components.size }

    fun componentCount(type: ComponentType): Int = engines.sumOf { engine ->
        engine.components.count { it.type == type }
    }
}

data class EngineRuleItem(
    val rule: GeneralRule,
    val components: List<ComponentInfo>,
    val hasManagedChanges: Boolean = false,
    val hasPersistentPolicy: Boolean = false,
) {
    val blockedCount: Int
        get() = components.count { !it.enabled() }

    val isEnabled: Boolean
        get() = components.any { it.enabled() }

    val isFullyBlocked: Boolean
        get() = components.isNotEmpty() && blockedCount == components.size

    /**
     * A safe-to-block rule is only a first-level signal. Android entry-point components can make
     * an otherwise benign SDK essential to an app, so they are never included in bulk blocking.
     */
    val riskLevel: EngineRiskLevel
        get() = when {
            components.any { it.isCoreEntryPoint() } -> EngineRiskLevel.HIGH_RISK
            rule.safeToBlock == true -> EngineRiskLevel.SAFE
            else -> EngineRiskLevel.CAUTION
        }
}

private fun ComponentInfo.isCoreEntryPoint(): Boolean {
    val identifier = "$name $simpleName".lowercase()
    return type == ComponentType.PROVIDER ||
        identifier.contains("mainactivity") ||
        identifier.contains("accessibilityservice") ||
        identifier.contains("notificationlistener") ||
        identifier.contains("vpnservice") ||
        identifier.contains("inputmethod") ||
        identifier.contains("deviceadmin") ||
        identifier.contains("accountauthenticator")
}

sealed interface EngineUiState {
    data class Loading(
        val progress: Float? = null,
    ) : EngineUiState

    data class Success(val apps: List<EngineAppItem>) : EngineUiState

    data class Error(val error: UiMessage) : EngineUiState
}
