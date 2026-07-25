package com.mondns.app

import java.io.File

/**
 * Pembungkus operasi file: coba cara normal (java.io.File) dulu, kalau gagal
 * -- biasanya karena Scoped Storage nolak akses ke Android/data|obb milik app
 * lain -- baru lempar ke [ShizukuManager] yang jalan dengan privilese shell.
 *
 * Auto-reconnect: kalau proses Shizuku sempat mati/putus di tengah jalan,
 * satu kali percobaan ulang otomatis dilakukan (invalidate + rebind) sebelum
 * benar-benar dianggap gagal.
 *
 * Setelah tiap panggilan, cek [lastFailReason] buat tau kenapa gagal --
 * dipakai UI (FileManagerFragment/MlbbFragment) buat nentuin pesan/dialog
 * panduan yang paling pas ke user, bukan cuma toast generik.
 */
object FileOps {

    enum class FailReason {
        NONE,                    // sukses (via normal ataupun Shizuku)
        SHIZUKU_NOT_INSTALLED,   // app Shizuku belum ada / belum aktif
        SHIZUKU_NO_PERMISSION,   // Shizuku aktif tapi izin belum diberikan
        SHIZUKU_ERROR            // Shizuku harusnya siap tapi tetap gagal (service error/proses shell nolak)
    }

    @Volatile
    var lastFailReason: FailReason = FailReason.NONE
        private set

    /** True kalau kegagalan terakhir bisa diselesaikan dengan mengaktifkan/memberi izin Shizuku. */
    fun needsShizukuSetup(): Boolean =
        lastFailReason == FailReason.SHIZUKU_NOT_INSTALLED || lastFailReason == FailReason.SHIZUKU_NO_PERMISSION

    fun delete(file: File): Boolean {
        val ok = try { file.deleteRecursively() } catch (e: Exception) { false }
        if (ok) { lastFailReason = FailReason.NONE; return true }
        return callShizuku { it.delete(file.absolutePath) }
    }

    fun copy(from: File, to: File, overwrite: Boolean = false): Boolean {
        val ok = try { from.copyRecursively(to, overwrite) } catch (e: Exception) { false }
        if (ok) { lastFailReason = FailReason.NONE; return true }
        return callShizuku { it.copy(from.absolutePath, to.absolutePath) }
    }

    fun move(from: File, to: File): Boolean {
        val ok = try {
            if (from.renameTo(to)) true
            else {
                val copied = from.copyRecursively(to, overwrite = false)
                if (copied) from.deleteRecursively()
                copied
            }
        } catch (e: Exception) {
            false
        }
        if (ok) { lastFailReason = FailReason.NONE; return true }
        return callShizuku { it.move(from.absolutePath, to.absolutePath) }
    }

    fun rename(from: File, to: File): Boolean {
        val ok = try { from.renameTo(to) } catch (e: Exception) { false }
        if (ok) { lastFailReason = FailReason.NONE; return true }
        return callShizuku { it.rename(from.absolutePath, to.absolutePath) }
    }

    fun mkdirs(dir: File): Boolean {
        val ok = try { dir.mkdirs() } catch (e: Exception) { false }
        if (ok) { lastFailReason = FailReason.NONE; return true }
        return callShizuku { it.mkdirs(dir.absolutePath) }
    }

    /** True kalau folder ini kemungkinan butuh Shizuku (Android/data atau Android/obb milik app lain). */
    fun looksProtected(path: String): Boolean {
        return Regex("/Android/(data|obb)/[^/]+").containsMatchIn(path)
    }

    /** Jalanin [block] lewat service Shizuku, dengan 1x auto-retry kalau koneksi putus di tengah. */
    private fun callShizuku(block: (IFileUserService) -> Boolean): Boolean {
        if (!ShizukuManager.isAvailable()) {
            lastFailReason = FailReason.SHIZUKU_NOT_INSTALLED
            return false
        }
        if (!ShizukuManager.isPermissionGranted()) {
            lastFailReason = FailReason.SHIZUKU_NO_PERMISSION
            return false
        }
        if (!ShizukuManager.ensureBound()) {
            lastFailReason = FailReason.SHIZUKU_ERROR
            return false
        }

        val svc = ShizukuManager.service ?: run {
            lastFailReason = FailReason.SHIZUKU_ERROR
            return false
        }

        return try {
            val result = block(svc)
            lastFailReason = if (result) FailReason.NONE else FailReason.SHIZUKU_ERROR
            result
        } catch (e: Exception) {
            // Kemungkinan proses shell Shizuku mati di tengah jalan -> buang binder lama,
            // coba nyambung ulang sekali, lalu ulangi panggilannya.
            ShizukuManager.invalidate()
            if (ShizukuManager.ensureBound()) {
                val svc2 = ShizukuManager.service
                if (svc2 != null) {
                    try {
                        val result = block(svc2)
                        lastFailReason = if (result) FailReason.NONE else FailReason.SHIZUKU_ERROR
                        return result
                    } catch (e2: Exception) {
                        lastFailReason = FailReason.SHIZUKU_ERROR
                        return false
                    }
                }
            }
            lastFailReason = FailReason.SHIZUKU_ERROR
            false
        }
    }
}
