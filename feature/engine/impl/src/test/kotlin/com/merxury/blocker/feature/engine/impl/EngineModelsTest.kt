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

import com.merxury.blocker.core.model.ComponentType
import com.merxury.blocker.core.model.data.AppItem
import com.merxury.blocker.core.model.data.ComponentInfo
import com.merxury.blocker.core.model.data.GeneralRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class EngineModelsTest {
    @Test
    fun engineState_reflectsMatchedComponentStates() {
        val rule = GeneralRule(id = 1, name = "Analytics", safeToBlock = true)
        val enabled = ComponentInfo("com.example", "Enabled", ComponentType.SERVICE)
        val blocked = ComponentInfo(
            "com.example",
            "Blocked",
            ComponentType.SERVICE,
            pmBlocked = true,
        )

        val item = EngineRuleItem(rule, listOf(enabled, blocked))

        assertTrue(item.isEnabled)
        assertFalse(item.isFullyBlocked)
        assertEquals(1, item.blockedCount)
    }

    @Test
    fun enabledRecommendedCount_excludesAlreadyBlockedRules() {
        val app = AppItem(label = "Example", packageName = "com.example")
        val enabledRule = GeneralRule(id = 1, name = "A", safeToBlock = true)
        val blockedRule = GeneralRule(id = 2, name = "B", safeToBlock = true)
        val cautionRule = GeneralRule(id = 3, name = "C", safeToBlock = false)
        val enabled = ComponentInfo("com.example", "Enabled", ComponentType.SERVICE)
        val blocked = ComponentInfo(
            "com.example",
            "Blocked",
            ComponentType.SERVICE,
            pmBlocked = true,
        )

        val item = EngineAppItem(
            app = app,
            engines = listOf(
                EngineRuleItem(enabledRule, listOf(enabled)),
                EngineRuleItem(blockedRule, listOf(blocked)),
                EngineRuleItem(cautionRule, listOf(enabled)),
            ),
        )

        assertEquals(2, item.recommendedCount)
        assertEquals(1, item.enabledRecommendedCount)
    }
}
