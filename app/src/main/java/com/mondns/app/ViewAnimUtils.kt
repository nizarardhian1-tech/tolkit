package com.mondns.app

import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator

/**
 * Memberi micro-interaction "tekan mengecil lalu balik" (press-scale) pada view
 * yang clickable (kartu dashboard, tombol, dsb). Dipanggil sekali saat view dibuat,
 * lalu otomatis bereaksi setiap kali disentuh -> terasa lebih hidup/premium
 * dibanding ripple polos, tanpa mengganggu OnClickListener yang sudah ada.
 */
fun View.applyPressFeedback(
    scaleDown: Float = 0.96f,
    downDuration: Long = 90L,
    upDuration: Long = 220L
) {
    setOnTouchListener { v, event ->
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                v.animate()
                    .scaleX(scaleDown)
                    .scaleY(scaleDown)
                    .setDuration(downDuration)
                    .start()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(upDuration)
                    .setInterpolator(OvershootInterpolator(2.2f))
                    .start()
            }
        }
        // false: jangan konsumsi event, biarkan OnClickListener/ripple tetap jalan normal
        false
    }
}
