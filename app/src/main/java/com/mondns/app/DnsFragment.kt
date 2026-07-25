package com.mondns.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.*
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.mondns.app.databinding.FragmentDnsBinding
import com.mondns.app.databinding.ItemDnsBinding

class DnsFragment : Fragment() {
    private var _binding: FragmentDnsBinding? = null
    private val binding get() = _binding!!

    private val dnsList = listOf(
        DnsServer("Cloudflare", "1dot1dot1dot1.cloudflare-dns.com", "Fastest & most private resolver."),
        DnsServer("Google", "dns.google", "Reliable & fast servers by Google."),
        DnsServer("AdGuard (Default)", "dns.adguard-dns.com", "Blocks ads, trackers, & malicious domains."),
        DnsServer("Quad9 (Recommended)", "dns.quad9.net", "High security, blocks malware & phishing."),
        DnsServer("NextDNS", "dns.nextdns.io", "Customizable DNS (Requires NextDNS account)."),
        DnsServer("Control D", "freedns.controld.com", "Blocks ads, trackers, & analytics.")
    )

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDnsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val adapter = DnsAdapter(dnsList) { selectedDns ->
            val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("DNS Hostname", selectedDns.hostname))
            Toast.makeText(requireContext(), "Copied: ${selectedDns.hostname}\nPaste this in Private DNS", Toast.LENGTH_LONG).show()
            try {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
        binding.rvDns.layoutManager = LinearLayoutManager(requireContext())
        binding.rvDns.adapter = adapter
    }

    override fun onDestroyView() { super.onDestroyView(); _binding = null }

    inner class DnsAdapter(private val list: List<DnsServer>, private val onSetClicked: (DnsServer) -> Unit) : RecyclerView.Adapter<DnsAdapter.ViewHolder>() {
        inner class ViewHolder(val itemBinding: ItemDnsBinding) : RecyclerView.ViewHolder(itemBinding.root)
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemDnsBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val dns = list[position]
            holder.itemBinding.tvDnsName.text = dns.name
            holder.itemBinding.tvDnsDesc.text = dns.description
            holder.itemBinding.tvHostname.text = dns.hostname
            holder.itemBinding.btnCopySet.setOnClickListener { onSetClicked(dns) }
            holder.itemView.setOnClickListener { onSetClicked(dns) }
        }
        override fun getItemCount() = list.size
    }
}