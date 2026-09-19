package com.dispatch.driver.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        PendingEventEntity::class,
        CachedDriverStateEntity::class,
        CachedAssignmentEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class DispatchDatabase : RoomDatabase() {
    abstract fun statusOutboxDao(): StatusOutboxDao
}
