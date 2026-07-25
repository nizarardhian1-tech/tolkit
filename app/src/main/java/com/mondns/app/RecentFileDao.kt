package com.mondns.app

import androidx.room.*

@Dao
interface RecentFileDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: RecentFileEntry)

    @Query("SELECT * FROM recent_files ORDER BY lastOpenedAt DESC")
    fun getAll(): List<RecentFileEntry>

    @Delete
    fun delete(entry: RecentFileEntry)

    @Query("DELETE FROM recent_files")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM recent_files")
    fun count(): Int

    @Query("DELETE FROM recent_files WHERE uri IN (SELECT uri FROM recent_files ORDER BY lastOpenedAt ASC LIMIT :n)")
    fun deleteOldest(n: Int)
}
