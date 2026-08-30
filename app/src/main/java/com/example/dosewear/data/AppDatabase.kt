package com.example.dosewear.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [Supplement::class, Reminder::class, ReminderItem::class, DoseLog::class],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun supplementDao(): SupplementDao
    abstract fun reminderDao(): ReminderDao
    abstract fun doseLogDao(): DoseLogDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dosewear.db"
                )
                    // Varsayilan WAL kullaniliyor: okuma ve yazma birbirini
                    // engellemiyor. TRUNCATE moduyla Flow sorgulari yazmalarin
                    // arkasinda kuyruga giriyor ve arayuz takiliyordu.
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
