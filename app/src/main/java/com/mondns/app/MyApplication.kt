package com.mondns.app

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MyApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Paling pertama: pasang crash logger, sebelum apa pun lain diinisialisasi,
        // supaya crash yang terjadi sedini mungkin dalam siklus hidup app tetap tertangkap.
        CrashLogger.install(this)

        // Terapkan night mode SEBELUM Activity manapun dibuat (termasuk SplashActivity).
        // Kalau ini dipanggil di MainActivity.onCreate() seperti sebelumnya, dan mode
        // yang diminta berbeda dari mode saat itu (default: MODE_NIGHT_UNSPECIFIED),
        // AppCompatDelegate akan otomatis me-recreate() activity yang sedang berjalan.
        // Itu penyebab transisi splash -> home terasa "nge-refresh" / kedip.
        // Dengan menerapkannya di sini (Application.onCreate, sebelum ada Activity),
        // tidak ada recreate() yang terjadi karena belum ada activity untuk di-recreate.
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        // Buka MyApplication.kt:
        // Dari: val isDark = prefs.getBoolean("isDark", false)
        // Ubah Menjadi:
        val isDark = prefs.getBoolean("isDark", true)
        AppCompatDelegate.setDefaultNightMode(
            if (isDark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
    }
}
