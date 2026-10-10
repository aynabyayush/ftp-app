package com.ayush.ftpclient

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ayush.ftpclient.databinding.ItemFolderEntryBinding
import java.io.File

class FolderAdapter(
    private val onFolderClick: (File) -> Unit
) : RecyclerView.Adapter<FolderAdapter.FolderViewHolder>() {

    private val folders = mutableListOf<File>()

    fun submitList(newList: List<File>) {
        folders.clear()
        folders.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): FolderViewHolder {
        val binding = ItemFolderEntryBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return FolderViewHolder(binding)
    }

    override fun onBindViewHolder(holder: FolderViewHolder, position: Int) {
        holder.bind(folders[position])
    }

    override fun getItemCount(): Int = folders.size

    inner class FolderViewHolder(private val binding: ItemFolderEntryBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(folder: File) {
            binding.tvFolderName.text = folder.name
            binding.root.setOnClickListener { onFolderClick(folder) }
        }
    }
}
