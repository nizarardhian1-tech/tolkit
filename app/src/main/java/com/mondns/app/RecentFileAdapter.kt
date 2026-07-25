package com.mondns.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mondns.app.databinding.ItemRecentFileBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecentFileAdapter(
    private var items: List<RecentFileEntry>,
    private val onClick: (RecentFileEntry) -> Unit,
    private val onDelete: (RecentFileEntry) -> Unit
) : RecyclerView.Adapter<RecentFileAdapter.VH>() {

    private val dateFormat = SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())

    inner class VH(val binding: ItemRecentFileBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemRecentFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = items[position]
        val ctx = holder.binding.root.context
        val isFolder = entry.type == "folder"

        holder.binding.tvTitle.text = entry.displayName
        holder.binding.tvTypeBadge.text = if (isFolder) {
            ctx.getString(R.string.recent_file_type_folder)
        } else {
            ctx.getString(R.string.recent_file_type_file)
        }
        holder.binding.ivTypeIcon.setImageResource(
            if (isFolder) R.drawable.ic_open_folder else R.drawable.ic_open_file
        )
        val subtitle = if (isFolder && entry.entryRelPath.isNotEmpty()) {
            "→ ${entry.entryRelPath}  ·  ${dateFormat.format(Date(entry.lastOpenedAt))}"
        } else {
            dateFormat.format(Date(entry.lastOpenedAt))
        }
        holder.binding.tvMeta.text = subtitle

        holder.binding.root.setOnClickListener { onClick(entry) }
        holder.binding.btnDeleteEntry.setOnClickListener { onDelete(entry) }
    }

    override fun getItemCount(): Int = items.size

    fun submitList(newItems: List<RecentFileEntry>) {
        items = newItems
        notifyDataSetChanged()
    }
}
