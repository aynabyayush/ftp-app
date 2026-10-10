package com.ayush.ftpclient

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ayush.ftpclient.databinding.ItemSavedServerBinding

class SavedServerAdapter(
    private val onItemClick: (SavedFtpServer) -> Unit,
    private val onDeleteClick: (SavedFtpServer) -> Unit
) : RecyclerView.Adapter<SavedServerAdapter.ViewHolder>() {

    private val items = mutableListOf<SavedFtpServer>()

    fun submitList(newList: List<SavedFtpServer>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSavedServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemSavedServerBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(server: SavedFtpServer) {
            binding.tvSavedServerAlias.text = if (server.alias.isNotEmpty()) server.alias else server.host
            binding.tvSavedServerAddress.text = "ftp://${server.host}:${server.port}"

            if (server.isOnline) {
                binding.tvServerStatusBadge.text = "🟢 ONLINE"
                binding.tvServerStatusBadge.setTextColor(0xFF00FF66.toInt())
            } else {
                binding.tvServerStatusBadge.text = "🔴 OFFLINE"
                binding.tvServerStatusBadge.setTextColor(0xFFFF007F.toInt())
            }

            binding.root.setOnClickListener { onItemClick(server) }
            binding.btnDeleteSavedServer.setOnClickListener { onDeleteClick(server) }
        }
    }
}
