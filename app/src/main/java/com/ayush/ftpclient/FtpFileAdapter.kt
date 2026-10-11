package com.ayush.ftpclient

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.ayush.ftpclient.databinding.ItemFtpFileBinding
import org.apache.commons.net.ftp.FTPFile

class FtpFileAdapter(
    private val onItemClick: (FTPFile) -> Unit,
    private val onDownloadClick: (FTPFile) -> Unit,
    private val onMoreClick: (FTPFile) -> Unit
) : RecyclerView.Adapter<FtpFileAdapter.ViewHolder>() {

    private val files = mutableListOf<FTPFile>()
    var isGridView = false

    fun submitList(newList: List<FTPFile>) {
        files.clear()
        files.addAll(newList)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemFtpFileBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(files[position])
    }

    override fun getItemCount(): Int = files.size

    inner class ViewHolder(private val binding: ItemFtpFileBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(file: FTPFile) {
            binding.tvFileName.text = file.name

            if (file.isDirectory) {
                binding.imgType.setImageResource(R.drawable.ic_folder)
                binding.tvFileDetails.text = "Directory"
                binding.btnDownload.visibility = View.GONE
            } else {
                binding.imgType.setImageResource(R.drawable.ic_file)
                binding.tvFileDetails.text = HistoryManager.formatFileSize(file.size)
                binding.btnDownload.visibility = View.VISIBLE
            }

            binding.root.setOnClickListener { onItemClick(file) }
            binding.btnDownload.setOnClickListener { onDownloadClick(file) }
            binding.btnFtpItemMore.setOnClickListener { onMoreClick(file) }
        }
    }
}
