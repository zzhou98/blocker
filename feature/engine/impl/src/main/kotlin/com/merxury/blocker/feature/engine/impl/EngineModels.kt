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
import com.merxury.blocker.core.ui.data.UiMessage

data class EngineAppItem(
    val app: AppItem,
    val engines: List<EngineRuleItem>,
    val hasManagedChanges: Boolean = false,
) {
    val recommendedCount: Int
        get() = engines.count { it.rule.safeToBlock == true }

    val enabledRecommendedCount: Int
        get() = engines.count { it.rule.safeToBlock == true && it.isEnabled }
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
}

sealed interface EngineUiState {
    data class Loading(
        val progress: Float? = null,
    ) : EngineUiState

    data class Success(val apps: List<EngineAppItem>) : EngineUiState

    data class Error(val error: UiMessage) : EngineUiState
}
