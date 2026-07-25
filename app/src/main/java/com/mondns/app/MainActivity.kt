package com.mondns.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.mondns.app.databinding.ActivityMainBinding
import dagger.hilt.android.AndroidEntryPoint
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    companion object {
        init {
            System.loadLibrary("toolkit_security")
        }
    }

    private external fun getNativeUpdateUrl(): String

    override fun onCreate(savedInstanceState: Bundle?) {
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)

        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.navView.itemIconTintList = null

        ViewCompat.setOnApplyWindowInsetsListener(binding.main) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, 0, systemBars.right, systemBars.bottom)
            binding.appBarLayout.setPadding(0, systemBars.top, 0, 0)
            insets
        }

        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_theme) {
                val current = prefs.getBoolean("isDark", true)
                prefs.edit().putBoolean("isDark", !current).apply()
                AppCompatDelegate.setDefaultNightMode(if (!current) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
                true
            } else false
        }

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHost.navController
        val appBarConfig = AppBarConfiguration(navController.graph, binding.drawerLayout)
        binding.toolbar.setupWithNavController(navController, appBarConfig)

        binding.navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_telegram -> {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/modfreew")))
                    binding.drawerLayout.closeDrawers()
                    true
                }
                R.id.nav_share -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "MonToolkit")
                        putExtra(Intent.EXTRA_TEXT, "Hey! Check out MonToolkit. An all-in-one utility. Get it on our Telegram: https://t.me/modfreew")
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share App via"))
                    binding.drawerLayout.closeDrawers()
                    true
                }
                else -> {
                    val handled = NavigationUI.onNavDestinationSelected(item, navController)
                    if (handled) binding.drawerLayout.closeDrawers()
                    handled
                }
            }
        }

        checkAppUpdateState()
    }

    private fun checkAppUpdateState() {
        // 1. Local Build Time Expiration Check (Maximum 15 Days)
        if (isAppExpiredStrict()) {
            showStrictExpiredDialog("This build version has exceeded the 15-day expiration limit.")
            return
        }

        // 2. Online License & Update Check via Native Encrypted URL
        thread {
            try {
                val baseUrl = getNativeUpdateUrl()
                
                android.util.Log.d("MonToolkit_License", "Decrypted Native URL: '$baseUrl'")

                val cacheBusterUrl = if (baseUrl.contains("?")) {
                    "$baseUrl&_t=${System.currentTimeMillis()}"
                } else {
                    "$baseUrl?_t=${System.currentTimeMillis()}"
                }

                val url = URL(cacheBusterUrl)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.requestMethod = "GET"
                conn.useCaches = false
                conn.setRequestProperty("Cache-Control", "no-cache, no-store, must-revalidate")
                conn.setRequestProperty("Pragma", "no-cache")
                conn.setRequestProperty("User-Agent", "MonToolkit/${BuildConfig.VERSION_NAME}")

                val responseCode = conn.responseCode
                android.util.Log.d("MonToolkit_License", "HTTP Response Code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val text = conn.inputStream.bufferedReader().readText().trim()
                    android.util.Log.d("MonToolkit_License", "License file content: '$text'")

                    if (text.equals("true", ignoreCase = true)) {
                        runOnUiThread { showOptionalUpdateDialog() }
                    }
                } else if (responseCode == HttpURLConnection.HTTP_NOT_FOUND || responseCode == HttpURLConnection.HTTP_FORBIDDEN) {
                    // HTTP 404 / 403 -> Forced Expiration (License Revoked)
                    runOnUiThread {
                        showStrictExpiredDialog("This application license has been disabled or removed from the server.")
                    }
                }
            } catch (e: java.io.FileNotFoundException) {
                // File deleted on GitHub -> 404 FileNotFoundException
                android.util.Log.e("MonToolkit_License", "toolkit.txt not found on server (404)", e)
                runOnUiThread {
                    showStrictExpiredDialog("This application license has been disabled or removed from the server.")
                }
            } catch (e: Exception) {
                android.util.Log.e("MonToolkit_License", "Failed to check license online: ${e.message}", e)
            }
        }
    }

    private fun isAppExpiredStrict(): Boolean {
        return try {
            val buildTime = BuildConfig.BUILD_TIME
            val fifteenDaysInMillis = 15L * 24 * 60 * 60 * 1000L
            val expireDate = buildTime + fifteenDaysInMillis
            System.currentTimeMillis() > expireDate
        } catch (e: Exception) {
            false
        }
    }

    private fun showStrictExpiredDialog(reason: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle("App Expired ⌛")
            .setMessage("$reason\n\nPlease join our Telegram channel to download the latest build.")
            .setCancelable(false)
            .setPositiveButton("UPDATE NOW") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/modfreew"))
                startActivity(intent)
                finish()
            }
            .setNegativeButton("EXIT") { _, _ ->
                finish()
            }
            .show()
    }

    private fun showOptionalUpdateDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Update Available 🚀")
            .setMessage("A new version of MonToolkit is available!\n\nUpdating is recommended to get the latest features and bug fixes.")
            .setCancelable(true)
            .setPositiveButton("UPDATE NOW") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/modfreew"))
                startActivity(intent)
            }
            .setNegativeButton("LATER") { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}