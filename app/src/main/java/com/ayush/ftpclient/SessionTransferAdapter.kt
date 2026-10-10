package com.ayush.ftpclient

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ayush.ftpclient.databinding.ItemSessionTransferBinding

class SessionTransferAdapter(
    private val onItemClick: (CompletedSessionTransfer) -> Unit,
    private val onDeleteClick: (CompletedSessionTransfer) -> Unit
) : RecyclerView.Adapter<SessionTransferAdapter.ViewHolder>() {

    private val items = mutableListOf<CompletedSessionTransfer>()

    fun submitList(newList: List<CompletedSessionTransfer>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSessionTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemSessionTransferBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(item: CompletedSessionTransfer) {
            binding.tvSessionFileName.text = item.fileName
            binding.tvSessionDetails.text = "${item.formattedSize} • ${item.timestamp}"
            binding.tvStatusBadge.text = if (item.isUpload) "⬆ UPLOADED" else "⬇ DOWNLOADED"
            binding.tvStatusBadge.setTextColor(if (item.isUpload) 0xFFFF007F.toInt() else 0xFF00FF66.toInt())

            binding.root.setOnClickListener { onItemClick(item) }
            binding.btnDeleteSessionFile.setOnClickListener { onDeleteClick(item) }
        }
    }
}
