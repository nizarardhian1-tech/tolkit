package com.mondns.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.Toast
import androidx.fragment.app.Fragment
import com.mondns.app.databinding.FragmentPingBinding
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

class PingFragment : Fragment() {
    private var _binding: FragmentPingBinding? = null
    private val binding get() = _binding!!

    private var isPinging = false
    private var pingProcess: Process? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Prevent soft keyboard from obscuring focused input fields
        scrollToViewOnFocus(
            binding.etPingHost,
            binding.etPortHost,
            binding.etCustomPort,
            binding.etHttpUrl
        )

        // Mode Switching Listener (Ping vs Port Checker vs HTTP Header)
        binding.toggleNetMode.check(R.id.tabPing)
        binding.toggleNetMode.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            when (checkedId) {
                R.id.tabPing -> {
                    binding.containerPing.visibility = View.VISIBLE
                    binding.containerPort.visibility = View.GONE
                    binding.containerHttp.visibility = View.GONE
                }
                R.id.tabPort -> {
                    binding.containerPing.visibility = View.GONE
                    binding.containerPort.visibility = View.VISIBLE
                    binding.containerHttp.visibility = View.GONE
                }
                R.id.tabHttp -> {
                    binding.containerPing.visibility = View.GONE
                    binding.containerPort.visibility = View.GONE
                    binding.containerHttp.visibility = View.VISIBLE
                }
            }
        }

        // Setup Features
        setupNetworkInfoHeader()
        setupPingFeature()
        setupPortCheckerFeature()
        setupHttpInspectorFeature()
    }

    private fun scrollToViewOnFocus(vararg fields: EditText) {
        fields.forEach { field ->
            field.setOnFocusChangeListener { v, hasFocus ->
                if (hasFocus) {
                    v.postDelayed({
                        if (_binding == null) return@postDelayed
                        val scrollView = binding.netScrollView
                        val viewPos = IntArray(2)
                        val scrollPos = IntArray(2)
                        v.getLocationOnScreen(viewPos)
                        scrollView.getLocationOnScreen(scrollPos)
                        val targetY = scrollView.scrollY + (viewPos[1] - scrollPos[1]) - 140
                        scrollView.smoothScrollTo(0, targetY.coerceAtLeast(0))
                    }, 250)
                }
            }
        }
    }

    // ====================================================================
    // NETWORK INFO HEADER (Local IP, Network Type, Public IP)
    // ====================================================================

    private fun setupNetworkInfoHeader() {
        val (ip, type) = getLocalIpAndType()
        binding.tvNetInfoType.text = "Connection: $type"
        binding.tvNetInfoLocalIp.text = "Local IP: $ip"

        binding.btnGetPublicIp.setOnClickListener {
            binding.btnGetPublicIp.isEnabled = false
            binding.btnGetPublicIp.text = "Checking..."
            thread {
                val pubIp = try {
                    val conn = URL("https://api.ipify.org").openConnection() as HttpURLConnection
                    conn.connectTimeout = 4000
                    conn.readTimeout = 4000
                    conn.inputStream.bufferedReader().readText().trim()
                } catch (e: Exception) {
                    "Unavailable"
                }

                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    binding.btnGetPublicIp.isEnabled = true
                    binding.btnGetPublicIp.text = "Public IP"
                    binding.tvNetInfoLocalIp.text = "Local IP: $ip | Public: $pubIp"
                }
            }
        }
    }

    private fun getLocalIpAndType(): Pair<String, String> {
        var networkType = "Active"
        try {
            val cm = requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetwork
            if (activeNetwork != null) {
                val caps = cm.getNetworkCapabilities(activeNetwork)
                if (caps != null) {
                    networkType = when {
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                        else -> "Active"
                    }
                }
            }
        } catch (e: Exception) {
            networkType = "Connected"
        }

        var localIp = "Unknown"
        try {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        localIp = addr.hostAddress ?: "Unknown"
                        break
                    }
                }
            }
        } catch (e: Exception) {
            localIp = "127.0.0.1"
        }

        return Pair(localIp, networkType)
    }

    // ====================================================================
    // TAB 1: PING LATENCY TESTER
    // ====================================================================

    private fun setupPingFeature() {
        binding.btnStartPing.setOnClickListener {
            if (isPinging) {
                stopPing()
            } else {
                val host = binding.etPingHost.text?.toString()?.trim().orEmpty()
                if (host.isNotEmpty()) {
                    startPing(host)
                } else {
                    binding.etPingHost.error = "Enter host or IP"
                }
            }
        }
    }

    private fun startPing(host: String) {
        isPinging = true
        binding.btnStartPing.text = "STOP"
        binding.btnStartPing.setBackgroundColor(android.graphics.Color.parseColor("#D32F2F"))
        binding.tvPingConsole.text = "> Initiating ping to $host...\n"
        binding.etPingHost.isEnabled = false
        binding.cardPingSummary.visibility = View.GONE

        thread {
            try {
                pingProcess = Runtime.getRuntime().exec("ping -c 4 $host")
                val reader = BufferedReader(InputStreamReader(pingProcess!!.inputStream))
                var line: String?
                var rttMin = ""
                var rttAvg = ""
                var rttMax = ""

                while (reader.readLine().also { line = it } != null) {
                    if (!isPinging) break
                    val output = line ?: continue

                    if (output.contains("rtt min/avg/max") || output.contains("round-trip min/avg/max")) {
                        val stats = output.substringAfter("=").trim().substringBefore(" ").split("/")
                        if (stats.size >= 3) {
                            rttMin = "${stats[0]} ms"
                            rttAvg = "${stats[1]} ms"
                            rttMax = "${stats[2]} ms"
                        }
                    }

                    activity?.runOnUiThread {
                        if (_binding == null) return@runOnUiThread
                        binding.tvPingConsole.append("\n$output")
                        binding.scrollPingConsole.post { binding.scrollPingConsole.fullScroll(View.FOCUS_DOWN) }
                    }
                }

                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    if (rttAvg.isNotEmpty()) {
                        binding.tvPingMin.text = rttMin
                        binding.tvPingAvg.text = rttAvg
                        binding.tvPingMax.text = rttMax
                        binding.cardPingSummary.visibility = View.VISIBLE
                    }
                }
            } catch (e: Exception) {
                activity?.runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    binding.tvPingConsole.append("\n> Error: ${e.message}")
                }
            } finally {
                activity?.runOnUiThread { stopPing() }
            }
        }
    }

    private fun stopPing() {
        isPinging = false
        pingProcess?.destroy()
        if (_binding != null) {
            binding.btnStartPing.text = "PING"
            binding.btnStartPing.backgroundTintList = null
            binding.etPingHost.isEnabled = true
            binding.tvPingConsole.append("\n\n> Ping sequence completed.")
            binding.scrollPingConsole.post { binding.scrollPingConsole.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ====================================================================
    // TAB 2: PORT CHECKER
    // ====================================================================

    private fun setupPortCheckerFeature() {
        binding.btnCheckPort.setOnClickListener {
            val host = binding.etPortHost.text?.toString()?.trim().orEmpty()
            if (host.isEmpty()) {
                binding.etPortHost.error = "Enter host or IP"
                return@setOnClickListener
            }

            var port = when (binding.chipGroupPorts.checkedChipId) {
                R.id.chipPort80 -> 80
                R.id.chipPort443 -> 443
                R.id.chipPort22 -> 22
                R.id.chipPort8080 -> 8080
                R.id.chipPort3306 -> 3306
                R.id.chipPort53 -> 53
                else -> 80
            }

            val customPortStr = binding.etCustomPort.text?.toString()?.trim().orEmpty()
            if (customPortStr.isNotEmpty()) {
                val customP = customPortStr.toIntOrNull()
                if (customP != null && customP in 1..65535) {
                    port = customP
                }
            }

            checkPortStatus(host, port)
        }
    }

    private fun checkPortStatus(host: String, port: Int) {
        binding.btnCheckPort.isEnabled = false
        binding.btnCheckPort.text = "CHECKING..."
        binding.cardPortResult.visibility = View.GONE

        thread {
            var isOpen = false
            var responseTimeMs = -1L
            var errorDetail = ""

            try {
                val start = System.currentTimeMillis()
                val socket = Socket()
                socket.connect(InetSocketAddress(host, port), 3000)
                responseTimeMs = System.currentTimeMillis() - start
                isOpen = true
                socket.close()
            } catch (e: java.net.SocketTimeoutException) {
                errorDetail = "Connection Timed Out (Filtered/Firewalled)"
            } catch (e: java.net.ConnectException) {
                errorDetail = "Connection Refused (Port Closed)"
            } catch (e: Exception) {
                errorDetail = e.localizedMessage ?: "Connection Failed"
            }

            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                binding.btnCheckPort.isEnabled = true
                binding.btnCheckPort.text = "CHECK PORT STATUS"
                binding.cardPortResult.visibility = View.VISIBLE

                if (isOpen) {
                    binding.tvPortStatusChip.text = "PORT OPEN"
                    binding.tvPortStatusChip.setTextColor(android.graphics.Color.parseColor("#34D399"))
                    binding.tvPortStatusChip.setBackgroundResource(R.drawable.bg_patch_status_chip_done)
                    binding.tvPortDetail.text = "Host   : $host\nPort   : $port\nLatency: $responseTimeMs ms\nStatus : Reachable and accepting connections."
                } else {
                    binding.tvPortStatusChip.text = "PORT CLOSED / UNREACHABLE"
                    binding.tvPortStatusChip.setTextColor(android.graphics.Color.parseColor("#F87171"))
                    binding.tvPortStatusChip.setBackgroundResource(R.drawable.bg_patch_status_chip_failed)
                    binding.tvPortDetail.text = "Host   : $host\nPort   : $port\nReason : $errorDetail"
                }
            }
        }
    }

    // ====================================================================
    // TAB 3: HTTP HEADER INSPECTOR
    // ====================================================================

    private fun setupHttpInspectorFeature() {
        binding.btnInspectHttp.setOnClickListener {
            var urlStr = binding.etHttpUrl.text?.toString()?.trim().orEmpty()
            if (urlStr.isEmpty()) {
                binding.etHttpUrl.error = "Enter URL"
                return@setOnClickListener
            }

            if (!urlStr.startsWith("http://", ignoreCase = true) && !urlStr.startsWith("https://", ignoreCase = true)) {
                urlStr = "https://$urlStr"
            }

            inspectHttpHeaders(urlStr)
        }

        binding.btnCopyHttpHeaders.setOnClickListener {
            val text = binding.tvHttpHeaderResult.text.toString()
            if (text.isNotBlank()) {
                val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("HTTP Headers", text))
                Toast.makeText(requireContext(), "Headers copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun inspectHttpHeaders(urlString: String) {
        binding.btnInspectHttp.isEnabled = false
        binding.btnInspectHttp.text = "INSPECTING..."
        binding.cardHttpResult.visibility = View.GONE

        thread {
            val headerBuilder = StringBuilder()
            var statusCode = -1
            var statusMessage = ""

            try {
                val url = URL(urlString)
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 6000
                conn.readTimeout = 6000
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", "MonToolkit/NetworkStudio")

                statusCode = conn.responseCode
                statusMessage = conn.responseMessage ?: ""

                headerBuilder.append("HTTP/1.1 $statusCode $statusMessage\n\n")

                for ((key, values) in conn.headerFields) {
                    if (key != null) {
                        headerBuilder.append("$key: ${values.joinToString(", ")}\n")
                    }
                }
            } catch (e: Exception) {
                headerBuilder.append("Error inspecting headers: ${e.localizedMessage}")
            }

            activity?.runOnUiThread {
                if (_binding == null) return@runOnUiThread
                binding.btnInspectHttp.isEnabled = true
                binding.btnInspectHttp.text = "INSPECT HEADERS"
                binding.cardHttpResult.visibility = View.VISIBLE

                if (statusCode in 200..299) {
                    binding.tvHttpStatusBadge.text = "$statusCode $statusMessage"
                    binding.tvHttpStatusBadge.setTextColor(android.graphics.Color.parseColor("#34D399"))
                    binding.tvHttpStatusBadge.setBackgroundResource(R.drawable.bg_patch_status_chip_done)
                } else if (statusCode > 0) {
                    binding.tvHttpStatusBadge.text = "$statusCode $statusMessage"
                    binding.tvHttpStatusBadge.setTextColor(android.graphics.Color.parseColor("#FBBF24"))
                    binding.tvHttpStatusBadge.setBackgroundResource(R.drawable.bg_patch_status_chip)
                } else {
                    binding.tvHttpStatusBadge.text = "CONNECTION ERROR"
                    binding.tvHttpStatusBadge.setTextColor(android.graphics.Color.parseColor("#F87171"))
                    binding.tvHttpStatusBadge.setBackgroundResource(R.drawable.bg_patch_status_chip_failed)
                }

                binding.tvHttpHeaderResult.text = headerBuilder.toString().trim()
            }
        }
    }

    override fun onDestroyView() {
        stopPing()
        super.onDestroyView()
        _binding = null
    }
}