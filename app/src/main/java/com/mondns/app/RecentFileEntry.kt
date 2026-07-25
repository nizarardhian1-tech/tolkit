package com.mondns.app

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Satu entri "File Terakhir": file atau folder HTML yang pernah dibuka lewat
 * tombol "File"/"Folder". Sengaja dipisah dari [HtmlHistoryEntry] (Riwayat
 * Jalankan) karena keduanya konsepnya beda: ini daftar SUMBER (lokasi file),
 * bukan snapshot kode yang sudah dijalankan.
 */
@Entity(tableName = "recent_files")
data class RecentFileEntry(
    @PrimaryKey val uri: String,
    val displayName: String,
    val type: String, // "file" atau "folder"
    val entryRelPath: String, // hanya dipakai kalau type == "folder" (file HTML yang dipilih di dalamnya)
    val lastOpenedAt: Long
)
