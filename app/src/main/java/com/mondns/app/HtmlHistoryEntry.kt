package com.mondns.app

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Satu baris riwayat HTML Runner: snapshot kode yang pernah di-RUN, disimpan
 * otomatis di database lokal (Room/SQLite) supaya bisa dibuka lagi kapan pun
 * tanpa perlu paste ulang atau cari file lagi.
 */
@Entity(tableName = "html_history")
data class HtmlHistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val code: String,
    val sourceLabel: String,
    val createdAt: Long
)
