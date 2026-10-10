package com.ayush.ftpclient

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ayush.ftpclient.databinding.ItemStorageVolumeBinding
import java.io.File

class StorageVolumeAdapter(
    private val onVolumeClick: (File) -> Unit
) : RecyclerView.Adapter<StorageVolumeAdapter.ViewHolder>() {

    private val items = mutableListOf<File>()

    fun submitList(newList: List<File>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemStorageVolumeBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemStorageVolumeBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File) {
            val isInternal = file.absolutePath.contains("emulated") || file.absolutePath.contains("self")
            binding.tvVolumeName.text = if (isInternal) "Internal Storage" else "SD Card (${file.name})"
            binding.tvVolumePath.text = file.absolutePath
            binding.root.setOnClickListener { onVolumeClick(file) }
        }
    }
}
