package com.mondns.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mondns.app.databinding.ItemHtmlHistoryBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HtmlHistoryAdapter(
    private var items: List<HtmlHistoryEntry>,
    private val onClick: (HtmlHistoryEntry) -> Unit,
    private val onDelete: (HtmlHistoryEntry) -> Unit
) : RecyclerView.Adapter<HtmlHistoryAdapter.VH>() {

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale("id", "ID"))

    inner class VH(val binding: ItemHtmlHistoryBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemHtmlHistoryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        holder.binding.tvTitle.text = entry.title
        holder.binding.tvMeta.text = "${dateFormat.format(Date(entry.createdAt))}  ·  ${entry.sourceLabel}"
        holder.binding.tvSnippet.text = entry.code.take(140).replace("\n", " ")
        holder.binding.root.setOnClickListener { onClick(entry) }
        holder.binding.btnDeleteEntry.setOnClickListener { onDelete(entry) }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<HtmlHistoryEntry>) {
        items = newItems
        notifyDataSetChanged()
    }
}
