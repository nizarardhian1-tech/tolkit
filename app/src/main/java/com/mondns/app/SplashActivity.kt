package com.mondns.app

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity

/**
 * Splash simpel & modern: cukup wordmark "MonToolkit" fade-in + garis aksen
 * yang meluncur dari kecil ke penuh, tanpa ikon shield/badge ala aplikasi lama.
 * Background gradient sudah terpasang lewat Theme.App.Splash (windowBackground)
 * sehingga tidak ada black-screen sekilas antara proses start dan Activity ini
 * ditampilkan.
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        private const val HOLD_DURATION_MS = 900L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val tvWordmark = findViewById<android.widget.TextView>(R.id.tvSplashWordmark)
        val tvSubtitle = findViewById<android.widget.TextView>(R.id.tvSplashSubtitle)
        val accentBar = findViewById<android.view.View>(R.id.viewSplashAccentBar)

        // Wordmark fade-in + sedikit slide up
        tvWordmark.translationY = 16f
        tvWordmark.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(420L)
            .setInterpolator(DecelerateInterpolator())
            .start()

        // Subtitle menyusul sedikit lebih lambat
        tvSubtitle.animate()
            .alpha(0.75f)
            .setStartDelay(180L)
            .setDuration(380L)
            .start()

        // Accent bar "meluncur" (scaleX 0 -> 1) dari sisi kiri pivot
        accentBar.pivotX = 0f
        accentBar.animate()
            .scaleX(1f)
            .setStartDelay(320L)
            .setDuration(420L)
            .setInterpolator(OvershootInterpolator(1.6f))
            .start()

        // Setelah animasi selesai + jeda singkat, lanjut ke Dashboard dengan cross-fade
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            @Suppress("DEPRECATION")
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            finish()
        }, HOLD_DURATION_MS)
    }
}
