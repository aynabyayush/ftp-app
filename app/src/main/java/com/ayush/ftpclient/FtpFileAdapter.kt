package com.ayush.ftpclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ayush.ftpclient.databinding.ItemFtpFileBinding
import com.ayush.ftpclient.databinding.ItemFtpFileGridBinding
import org.apache.commons.net.ftp.FTPFile
import java.util.Locale

class FtpFileAdapter(
    private val onItemClick: (FTPFile) -> Unit,
    private val onDownloadClick: (FTPFile) -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        const val VIEW_TYPE_LIST = 0
        const val VIEW_TYPE_GRID = 1
    }

    private val items = mutableListOf<FTPFile>()
    var isGridView: Boolean = false
        set(value) {
            field = value
            notifyDataSetChanged()
        }

    fun submitList(newList: List<FTPFile>) {
        items.clear()
        items.addAll(newList)
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int {
        return if (isGridView) VIEW_TYPE_GRID else VIEW_TYPE_LIST
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_GRID) {
            val binding = ItemFtpFileGridBinding.inflate(inflater, parent, false)
            GridViewHolder(binding)
        } else {
            val binding = ItemFtpFileBinding.inflate(inflater, parent, false)
            ListViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val file = items[position]
        if (holder is ListViewHolder) {
            holder.bind(file)
        } else if (holder is GridViewHolder) {
            holder.bind(file)
        }
    }

    override fun getItemCount(): Int = items.size

    inner class ListViewHolder(private val binding: ItemFtpFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: FTPFile) {
            binding.tvFileName.text = file.name

            if (file.isDirectory) {
                binding.imgType.setImageResource(R.drawable.ic_folder)
                binding.tvFileDetails.text = "Directory"
                binding.btnDownload.visibility = View.GONE
            } else {
                binding.imgType.setImageResource(R.drawable.ic_file)
                val sizeFormatted = formatFileSize(file.size)
                binding.tvFileDetails.text = "File • $sizeFormatted"
                binding.btnDownload.visibility = View.VISIBLE
            }

            binding.root.setOnClickListener { onItemClick(file) }
            binding.btnDownload.setOnClickListener { onDownloadClick(file) }
        }
    }

    inner class GridViewHolder(private val binding: ItemFtpFileGridBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: FTPFile) {
            binding.tvFileName.text = file.name

            if (file.isDirectory) {
                binding.imgType.setImageResource(R.drawable.ic_folder)
                binding.tvFileDetails.text = "Folder"
                binding.btnDownload.visibility = View.GONE
            } else {
                binding.imgType.setImageResource(R.drawable.ic_file)
                val sizeFormatted = formatFileSize(file.size)
                binding.tvFileDetails.text = sizeFormatted
                binding.btnDownload.visibility = View.VISIBLE
            }

            binding.root.setOnClickListener { onItemClick(file) }
            binding.btnDownload.setOnClickListener { onDownloadClick(file) }
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
