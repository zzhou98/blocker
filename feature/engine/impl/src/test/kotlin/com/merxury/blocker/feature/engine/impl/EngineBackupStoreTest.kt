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
import androidx.test.core.app.ApplicationProvider
import com.merxury.blocker.core.model.data.ControllerType
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EngineBackupStoreTest {
    private lateinit var context: Context
    private lateinit var store: EngineBackupStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clearPreferences()
        store = EngineBackupStore(context)
    }

    @After
    fun tearDown() {
        clearPreferences()
    }

    @Test
    fun owners_areReferenceCountedByRule() {
        store.addOwner(PACKAGE, COMPONENT, 1)
        store.addOwner(PACKAGE, COMPONENT, 2)

        assertEquals(setOf(1, 2), store.getOwners(PACKAGE, COMPONENT))
        assertEquals(setOf(COMPONENT), store.ownedComponentNames(PACKAGE, 1))
        assertEquals(setOf(COMPONENT), store.managedComponentNames(PACKAGE))

        store.removeOwner(PACKAGE, COMPONENT, 1)

        assertEquals(setOf(2), store.getOwners(PACKAGE, COMPONENT))
        assertEquals(setOf(COMPONENT), store.managedComponentNames(PACKAGE))

        store.removeOwner(PACKAGE, COMPONENT, 2)

        assertTrue(store.getOwners(PACKAGE, COMPONENT).isEmpty())
        assertTrue(store.managedComponentNames(PACKAGE).isEmpty())
    }

    @Test
    fun addingSameOwnerTwice_isIdempotent() {
        store.addOwner(PACKAGE, COMPONENT, 7)
        store.addOwner(PACKAGE, COMPONENT, 7)

        assertEquals(setOf(7), store.getOwners(PACKAGE, COMPONENT))
    }

    @Test
    fun clearOwners_removesAllRuleOwnershipForComponent() {
        store.addOwner(PACKAGE, COMPONENT, 1)
        store.addOwner(PACKAGE, COMPONENT, 2)

        store.clearOwners(PACKAGE, COMPONENT)

        assertTrue(store.getOwners(PACKAGE, COMPONENT).isEmpty())
        assertTrue(store.ownedComponentNames(PACKAGE, 1).isEmpty())
        assertTrue(store.ownedComponentNames(PACKAGE, 2).isEmpty())
    }

    @Test
    fun controllerType_isStoredByFirstOwnerAndClearedWithLastOwner() {
        store.addOwner(
            PACKAGE,
            COMPONENT,
            1,
            controllerType = ControllerType.IFW,
            installToken = "install-a",
        )
        store.addOwner(
            PACKAGE,
            COMPONENT,
            2,
            controllerType = ControllerType.PM,
            installToken = "install-a",
        )

        assertEquals(ControllerType.IFW, store.getControllerType(PACKAGE, COMPONENT))

        store.removeOwner(PACKAGE, COMPONENT, 1)
        assertEquals(ControllerType.IFW, store.getControllerType(PACKAGE, COMPONENT))

        store.removeOwner(PACKAGE, COMPONENT, 2)
        assertEquals(null, store.getControllerType(PACKAGE, COMPONENT))
    }

    @Test
    fun reconcileInstallations_preservesInstalledAndRemovesUninstalled() {
        store.addOwner(PACKAGE, COMPONENT, 1, installToken = "install-a")
        store.addOwner(
            "com.removed",
            "com.removed.Service",
            2,
            installToken = "install-removed",
        )

        store.reconcileInstallations(mapOf(PACKAGE to "install-a"))

        assertEquals(setOf(COMPONENT), store.managedComponentNames(PACKAGE))
        assertTrue(store.managedComponentNames("com.removed").isEmpty())
    }

    @Test
    fun reconcileInstallations_clearsBackupAfterPackageReinstall() {
        store.addOwner(PACKAGE, COMPONENT, 1, installToken = "install-a")

        store.reconcileInstallations(mapOf(PACKAGE to "install-b"))

        assertTrue(store.managedComponentNames(PACKAGE).isEmpty())
        assertTrue(store.getOwners(PACKAGE, COMPONENT).isEmpty())
    }

    @Test
    fun reconcileInstallations_keepsBackupAcrossNormalUpdate() {
        store.addOwner(PACKAGE, COMPONENT, 1, installToken = "install-a")

        store.reconcileInstallations(mapOf(PACKAGE to "install-a"))

        assertEquals(setOf(COMPONENT), store.managedComponentNames(PACKAGE))
        assertEquals(setOf(1), store.getOwners(PACKAGE, COMPONENT))
    }

    private fun clearPreferences() {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    private companion object {
        const val PREF_NAME = "blocker_engine_backup"
        const val PACKAGE = "com.example"
        const val COMPONENT = "com.example.AnalyticsService"
    }
}
