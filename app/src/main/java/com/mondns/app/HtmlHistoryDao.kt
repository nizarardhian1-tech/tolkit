package com.mondns.app

import androidx.room.*

@Dao
interface HtmlHistoryDao {
    @Insert
    fun insert(entry: HtmlHistoryEntry): Long

    @Query("SELECT * FROM html_history ORDER BY createdAt DESC")
    fun getAll(): List<HtmlHistoryEntry>

    @Query("SELECT * FROM html_history ORDER BY createdAt DESC LIMIT 1")
    fun getLatest(): HtmlHistoryEntry?

    @Delete
    fun delete(entry: HtmlHistoryEntry)

    @Query("DELETE FROM html_history")
    fun deleteAll()

    @Query("SELECT COUNT(*) FROM html_history")
    fun count(): Int

    // Dipakai buat pruning otomatis: hapus entri paling lama begitu jumlah
    // riwayat melebihi batas (lihat MAX_HISTORY_ITEMS di HtmlRunnerFragment).
    @Query("DELETE FROM html_history WHERE id IN (SELECT id FROM html_history ORDER BY createdAt ASC LIMIT :n)")
    fun deleteOldest(n: Int)
}
