package com.mondns.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.mondns.app.databinding.FragmentHomeBinding

class HomeFragment : Fragment() {
    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var isFirstLaunch = true
    private var savedScrollY = 0

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Populate Live System Metrics Header
        updateLiveSystemMetrics()

        // Navigasi Kategori 1: Jaringan
        binding.cardDns.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_dns)
        }
        binding.cardPing.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_ping)
        }

        // Navigasi Kategori 2: Modding & Reverse
        binding.cardXpatch.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_xpatch)
        }
        binding.cardNativeTools.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_nativeTools)
        }
        binding.cardLuaEncryptor.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_luaEncryptor)
        }
        binding.cardConverter.setOnClickListener {
            findNavController().navigate(R.id.devConverterFragment)
        }

        // Navigasi Kategori 3: Alat & Utilitas
        binding.cardFileManager.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_fileManager)
        }
        binding.cardApk.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_apk)
        }
        binding.cardHtmlRunner.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_htmlRunner)
        }
        binding.cardMlbb.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_mlbb)
        }
        binding.cardApkSigner.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_apkSigner)
        }
        binding.cardSecurityScanner.setOnClickListener {
            findNavController().navigate(R.id.action_home_to_securityScanner)
        }

        // Micro-interaction: Efek membal saat kartu ditekan
        listOf(
            binding.cardDns,
            binding.cardPing,
            binding.cardXpatch,
            binding.cardNativeTools,
            binding.cardLuaEncryptor,
            binding.cardConverter,
            binding.cardFileManager,
            binding.cardApk,
            binding.cardHtmlRunner,
            binding.cardMlbb,
            binding.cardApkSigner,
            binding.cardSecurityScanner
        ).forEach { it.applyPressFeedback() }

        // Animasi cascade hanya saat pertama kali dibuka
        if (isFirstLaunch) {
            binding.homeContentRoot.scheduleLayoutAnimation()
            isFirstLaunch = false
        } else {
            binding.homeContentRoot.post {
                binding.homeContentRoot.scrollTo(0, savedScrollY)
            }
        }
    }

    private fun updateLiveSystemMetrics() {
        if (_binding == null) return

        // 1. Architecture & Android API Level
        val primaryAbi = if (Build.SUPPORTED_ABIS.isNotEmpty()) Build.SUPPORTED_ABIS[0] else "arm64-v8a"
        binding.tvDeviceArch.text = "$primaryAbi · API ${Build.VERSION.SDK_INT}"

        // 2. Shizuku Status
        val isShizukuReady = try {
            ShizukuManager.isReady()
        } catch (e: Exception) {
            false
        }

        if (isShizukuReady) {
            binding.tvShizukuStatus.text = "Active (Shell)"
            binding.tvShizukuStatus.setTextColor(android.graphics.Color.parseColor("#34D399"))
            binding.dotShizuku.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#34D399"))
        } else {
            binding.tvShizukuStatus.text = "Inactive"
            binding.tvShizukuStatus.setTextColor(android.graphics.Color.parseColor("#FBBF24"))
            binding.dotShizuku.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#FBBF24"))
        }

        // 3. Network Local IP
        var localIp = "Disconnected"
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        localIp = addr.hostAddress ?: "127.0.0.1"
                        break
                    }
                }
            }
        } catch (_: Exception) { }

        binding.tvSystemIp.text = localIp
    }

    override fun onDestroyView() {
        savedScrollY = binding.homeContentRoot.scrollY
        super.onDestroyView()
        _binding = null
    }
}