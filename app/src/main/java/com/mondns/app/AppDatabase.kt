package com.mondns.app

import android.content.Context
import androidx.room.*

@Database(entities = [User::class, HtmlHistoryEntry::class, RecentFileEntry::class], version = 3, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun userDao(): UserDao
    abstract fun htmlHistoryDao(): HtmlHistoryDao
    abstract fun recentFileDao(): RecentFileDao
    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun getInstance(context: Context): AppDatabase = instance ?: synchronized(this) { instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "app_db").fallbackToDestructiveMigration().build().also { instance = it } }
    }
}
