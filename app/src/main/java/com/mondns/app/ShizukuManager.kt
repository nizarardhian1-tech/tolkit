package com.mondns.app

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

/**
 * Titik pusat integrasi Shizuku. Dipakai dari FileManagerFragment (dan bagian
 * lain kalau perlu) buat cek status, minta izin, dan konek ke [FileUserService]
 * yang jalan dengan privilese shell.
 *
 * Prasyarat di device user:
 *  1. App "Shizuku" ke-install (Play Store / GitHub rikkaapps/Shizuku)
 *  2. Shizuku aktif (via wireless debugging pairing, ADB command, atau root)
 */
object ShizukuManager {

    const val REQUEST_CODE = 9100

    @Volatile
    var service: IFileUserService? = null
        private set

    private var listenersRegistered = false

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, FileUserService::class.java.name))
            .daemon(false)
            .processNameSuffix("shizuku_file_service")
            .debuggable(BuildConfig.DEBUG)
            .version(1)
    }

    // Kalau proses shell tempat FileUserService jalan mati mendadak (mis. Shizuku
    // di-restart user, device tidur lama, dll), binder ini yang kasih tahu kita
    // supaya "service" langsung di-null-kan alih-alih dipakai lagi dalam
    // keadaan mati (nyebabin RemoteException/DeadObjectException).
    private val deathRecipient = IBinder.DeathRecipient {
        service = null
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) {
                try {
                    binder.linkToDeath(deathRecipient, 0)
                } catch (e: Throwable) {
                    // ignore, worst case death recipient gak aktif tapi service tetap dipakai
                }
                IFileUserService.Stub.asInterface(binder)
            } else null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
        }
    }

    /** True kalau app Shizuku ke-install dan servicenya hidup. */
    fun isAvailable(): Boolean = try {
        Shizuku.pingBinder()
    } catch (e: Throwable) {
        false
    }

    /** True kalau app kita sudah dikasih izin oleh Shizuku. */
    fun isPermissionGranted(): Boolean = try {
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (e: Throwable) {
        false
    }

    /** Siap dipakai = Shizuku hidup DAN izin sudah granted DAN service sudah nyambung. */
    fun isReady(): Boolean = isAvailable() && isPermissionGranted() && service != null

    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    fun requestPermission() {
        if (isAvailable() && !isPermissionGranted()) {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    fun bindService() {
        if (isAvailable() && isPermissionGranted() && service == null) {
            try {
                Shizuku.bindUserService(userServiceArgs, connection)
            } catch (e: Throwable) {
                service = null
            }
        }
    }

    /** Dipanggil FileOps kalau satu panggilan AIDL gagal (proses shell mati/dll),
     * supaya percobaan berikutnya gak makai binder yang udah gak valid. */
    fun invalidate() {
        service = null
    }

    /**
     * Coba pastikan service kebind, TERMASUK auto-reconnect kalau sempat putus.
     * Nunggu sebentar (maks [timeoutMs]) karena bindUserService() async.
     * Aman dipanggil dari thread background; JANGAN panggil dari main thread
     * kalau operasi butuh instan (dipakai lewat FileOps yang sudah background).
     */
    fun ensureBound(timeoutMs: Long = 1500): Boolean {
        if (!isAvailable() || !isPermissionGranted()) return false
        if (service != null) return true
        bindService()
        val stepMs = 50L
        var waited = 0L
        while (service == null && waited < timeoutMs) {
            try { Thread.sleep(stepMs) } catch (e: InterruptedException) { break }
            waited += stepMs
        }
        return service != null
    }

    fun unbindService() {
        try {
            Shizuku.unbindUserService(userServiceArgs, connection, true)
        } catch (e: Throwable) {
            // ignore, service mungkin memang belum konek
        }
        service = null
    }
}
