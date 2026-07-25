package com.mondns.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.mondns.app.databinding.ItemListBinding

class MyAdapter(private val dataList: List<String>, private val onItemClick: (String, Int) -> Unit = { _, _ -> }) : RecyclerView.Adapter<MyAdapter.ViewHolder>() {
    inner class ViewHolder(val binding: ItemListBinding) : RecyclerView.ViewHolder(binding.root)
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = ViewHolder(ItemListBinding.inflate(LayoutInflater.from(parent.context), parent, false))
    override fun onBindViewHolder(holder: ViewHolder, position: Int) { holder.binding.tvTitle.text = dataList[position]; holder.binding.tvSubtitle.text = "Item #${position + 1}"; holder.itemView.setOnClickListener { onItemClick(dataList[position], position) } }
    override fun getItemCount() = dataList.size
}
