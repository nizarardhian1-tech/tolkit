package com.mondns.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/**
 * Menerima callback dari PackageInstaller.Session.commit(...) yang dipakai
 * ApkInstaller.installSplitApkSet(). Kalau butuh konfirmasi user, lempar ke dialog
 * konfirmasi sistem; kalau sudah final, kasih tahu hasilnya lewat Toast.
 */
class InstallResultReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_INSTALL_STATUS = "com.mondns.app.action.INSTALL_STATUS"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)

        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                confirmIntent?.let {
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(it)
                    } catch (_: Exception) {
                        Toast.makeText(context, context.getString(R.string.installer_failed_format, ""), Toast.LENGTH_LONG).show()
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Toast.makeText(context, context.getString(R.string.installer_success), Toast.LENGTH_SHORT).show()
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
                Toast.makeText(context, context.getString(R.string.installer_failed_format, message), Toast.LENGTH_LONG).show()
            }
        }
    }
}
