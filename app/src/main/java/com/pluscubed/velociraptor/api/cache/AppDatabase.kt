package com.pluscubed.velociraptor.api.cache

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [Way::class], version = 8)
abstract class AppDatabase : RoomDatabase() {
    abstract fun wayDao(): WayDao
}