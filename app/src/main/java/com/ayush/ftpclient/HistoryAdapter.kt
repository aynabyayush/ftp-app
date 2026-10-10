package com.ayush.ftpclient

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ayush.ftpclient.databinding.ItemHistoryEntryBinding

class HistoryAdapter(
    private val onItemClick: (HistoryItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.HistoryViewHolder>() {

    private val items = mutableListOf<HistoryItem>()

    fun submitList(newList: List<HistoryItem>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): HistoryViewHolder {
        val binding = ItemHistoryEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return HistoryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: HistoryViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class HistoryViewHolder(private val binding: ItemHistoryEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: HistoryItem) {
            when (item.type) {
                HistoryManager.TYPE_RECEIVED -> {
                    binding.tvHistoryBadge.text = "⬇ RECEIVED"
                    binding.tvHistoryBadge.setTextColor(0xFF00F0FF.toInt())
                }
                HistoryManager.TYPE_SENT -> {
                    binding.tvHistoryBadge.text = "⬆ SENT"
                    binding.tvHistoryBadge.setTextColor(0xFFFF007F.toInt())
                }
                else -> {
                    binding.tvHistoryBadge.text = "🔗 CONNECTION"
                    binding.tvHistoryBadge.setTextColor(0xFF00FF66.toInt())
                }
            }

            binding.tvHistoryFileName.text = item.fileName

            val detailsText = if (item.fileSize.isNotEmpty()) {
                "${item.details} • ${item.fileSize}"
            } else {
                item.details
            }
            binding.tvHistoryDetails.text = detailsText
            binding.tvHistoryTime.text = item.timestamp

            binding.root.setOnClickListener { onItemClick(item) }
        }
    }
}
