package com.dispatch.driver.core.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [PendingEventEntity::class, CachedDriverStateEntity::class, CachedCapabilityDocumentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class DispatchDatabase : RoomDatabase() {
    abstract fun outbox(): OutboxDao

    companion object {
        /**
         * The on-device store. Excluded from backup and device transfer by
         * `data_extraction_rules.xml`: an outbox restored onto another handset
         * would replay this device's sequence numbers from a different one.
         */
        fun open(context: Context): DispatchDatabase =
            Room.databaseBuilder(context, DispatchDatabase::class.java, "dispatch.db").build()
    }
}
