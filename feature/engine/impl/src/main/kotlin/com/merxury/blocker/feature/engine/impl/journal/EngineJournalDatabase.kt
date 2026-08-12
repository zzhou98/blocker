/*
 * Copyright 2026 Blocker
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.merxury.blocker.feature.engine.impl.journal

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Durable Engine operation journal. A row is written before Android component state is changed,
 * allowing interrupted work to be inspected or recovered on the next Engine visit.
 */
@Database(
    entities = [EngineOperationEntity::class, EnginePolicyEntity::class, EngineScanSnapshotEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class EngineJournalDatabase : RoomDatabase() {
    abstract fun journalDao(): EngineJournalDao
}

@Entity(
    tableName = "engine_operation",
    indices = [Index(value = ["packageName", "status"]), Index(value = ["createdAt"])]
)
data class EngineOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val ruleId: Int,
    val action: String,
    val status: String,
    val componentCount: Int,
    val completedCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = createdAt,
)

/** Desired state, independent of the currently installed applications. */
@Entity(tableName = "engine_policy", primaryKeys = ["scope", "packageName", "ruleId"])
data class EnginePolicyEntity(
    val scope: String,
    val packageName: String,
    val ruleId: Int,
    val mode: String,
    val autoApply: Boolean,
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "engine_scan_snapshot")
data class EngineScanSnapshotEntity(
    @PrimaryKey val packageName: String,
    val versionCode: Long,
    val lastUpdateTime: Long,
    val componentHash: String,
    val ruleHash: String,
    val scannedAt: Long = System.currentTimeMillis(),
)

@Dao
interface EngineJournalDao {
    @Insert
    suspend fun insertOperation(operation: EngineOperationEntity): Long

    @Query("UPDATE engine_operation SET completedCount = :completedCount, status = :status, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateOperation(id: Long, completedCount: Int, status: String, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM engine_operation WHERE packageName = :packageName AND status = 'RUNNING' ORDER BY createdAt DESC")
    suspend fun runningOperations(packageName: String): List<EngineOperationEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPolicy(policy: EnginePolicyEntity)

    @Query("DELETE FROM engine_policy WHERE scope = :scope AND packageName = :packageName AND ruleId = :ruleId")
    suspend fun deletePolicy(scope: String, packageName: String, ruleId: Int)

    @Query("SELECT * FROM engine_policy WHERE (scope = 'GLOBAL' OR packageName = :packageName) AND mode = 'BLOCK' ORDER BY scope DESC")
    suspend fun blockingPolicies(packageName: String): List<EnginePolicyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSnapshot(snapshot: EngineScanSnapshotEntity)

    @Query("SELECT * FROM engine_scan_snapshot WHERE packageName = :packageName")
    suspend fun snapshot(packageName: String): EngineScanSnapshotEntity?
}

@Module
@InstallIn(SingletonComponent::class)
internal object EngineJournalModule {
    @Provides
    @Singleton
    fun provideEngineJournalDatabase(@ApplicationContext context: Context): EngineJournalDatabase =
        Room.databaseBuilder(context, EngineJournalDatabase::class.java, "engine_journal")
            .fallbackToDestructiveMigration(true)
            .build()

    @Provides
    fun provideEngineJournalDao(database: EngineJournalDatabase): EngineJournalDao = database.journalDao()
}
