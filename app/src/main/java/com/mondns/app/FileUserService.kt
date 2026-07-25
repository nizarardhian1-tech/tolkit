package com.mondns.app

import java.io.File
import kotlin.system.exitProcess

/**
 * Jalan di proses shell (uid 2000) yang di-spawn oleh Shizuku, BUKAN di proses
 * app biasa. Karena itu class ini bisa baca/tulis file yang ditolak Scoped
 * Storage kalau dipanggil langsung dari proses app (mis. Android/data/<pkg
 * lain>). Jangan taruh logic UI atau apapun yang butuh Context app di sini.
 *
 * Constructor kosong WAJIB ada — Shizuku membuat instance ini lewat reflection.
 */
class FileUserService : IFileUserService.Stub() {

    override fun exists(path: String): Boolean = File(path).exists()

    override fun isDirectory(path: String): Boolean = File(path).isDirectory

    override fun isFile(path: String): Boolean = File(path).isFile

    override fun length(path: String): Long = File(path).length()

    override fun lastModified(path: String): Long = File(path).lastModified()

    override fun list(path: String): Array<String> = File(path).list() ?: emptyArray()

    override fun mkdirs(path: String): Boolean = File(path).mkdirs()

    override fun delete(path: String): Boolean {
        return try {
            File(path).deleteRecursively()
        } catch (e: Exception) {
            false
        }
    }

    override fun copy(fromPath: String, toPath: String): Boolean {
        return try {
            File(fromPath).copyRecursively(File(toPath), overwrite = true)
        } catch (e: Exception) {
            false
        }
    }

    override fun move(fromPath: String, toPath: String): Boolean {
        return try {
            val from = File(fromPath)
            val to = File(toPath)
            if (from.renameTo(to)) return true
            // renameTo bisa gagal kalau beda partisi/volume -> copy lalu hapus asal
            val copied = from.copyRecursively(to, overwrite = true)
            if (copied) from.deleteRecursively()
            copied
        } catch (e: Exception) {
            false
        }
    }

    override fun rename(fromPath: String, toPath: String): Boolean {
        return try {
            File(fromPath).renameTo(File(toPath))
        } catch (e: Exception) {
            false
        }
    }

    override fun destroy() {
        exitProcess(0)
    }
}
