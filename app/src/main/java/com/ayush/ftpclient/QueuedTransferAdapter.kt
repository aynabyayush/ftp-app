package com.ayush.ftpclient

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ayush.ftpclient.databinding.ItemQueueTransferBinding
import org.apache.commons.net.ftp.FTPFile

class QueuedTransferAdapter(
    private val onCancelClick: (FTPFile) -> Unit
) : RecyclerView.Adapter<QueuedTransferAdapter.ViewHolder>() {

    private val items = mutableListOf<FTPFile>()

    fun submitList(newList: List<FTPFile>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemQueueTransferBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemQueueTransferBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: FTPFile) {
            binding.tvQueueFileName.text = file.name
            binding.btnCancelQueued.setOnClickListener { onCancelClick(file) }
        }
    }
}
