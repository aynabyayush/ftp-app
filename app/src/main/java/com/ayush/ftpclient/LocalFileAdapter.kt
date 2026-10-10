package com.ayush.ftpclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ayush.ftpclient.databinding.ItemLocalFileBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class TileDetailMode {
    SIZE,
    DATE,
    DIRECTORY,
    NONE
}

class LocalFileAdapter(
    private val onFileClick: (File) -> Unit,
    private val onMoreClick: (File, View) -> Unit
) : RecyclerView.Adapter<LocalFileAdapter.ViewHolder>() {

    private val items = mutableListOf<File>()
    val selectedDetailModes = mutableSetOf<TileDetailMode>(TileDetailMode.SIZE)

    fun submitList(newList: List<File>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLocalFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    override fun getItemCount(): Int = items.size

    inner class ViewHolder(private val binding: ItemLocalFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: File) {
            binding.tvLocalFileName.text = file.name

            if (file.isDirectory) {
                binding.ivLocalIcon.setImageResource(R.drawable.ic_folder)
            } else {
                binding.ivLocalIcon.setImageResource(R.drawable.ic_file)
            }

            val detailParts = mutableListOf<String>()
            if (selectedDetailModes.contains(TileDetailMode.SIZE)) {
                detailParts.add(if (file.isDirectory) "Folder" else HistoryManager.formatFileSize(file.length()))
            }
            if (selectedDetailModes.contains(TileDetailMode.DATE)) {
                detailParts.add(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified())))
            }
            if (selectedDetailModes.contains(TileDetailMode.DIRECTORY)) {
                detailParts.add(file.parent ?: file.absolutePath)
            }

            val detailText = detailParts.joinToString(" • ")

            binding.tvLocalFileDetails.text = detailText
            binding.tvLocalFileDetails.visibility = if (detailText.isEmpty()) View.GONE else View.VISIBLE

            binding.root.setOnClickListener { onFileClick(file) }
            binding.btnLocalFileMore.setOnClickListener { v -> onMoreClick(file, v) }
        }
    }
}
