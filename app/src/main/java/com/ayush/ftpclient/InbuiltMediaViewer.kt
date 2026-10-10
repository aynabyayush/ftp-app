package com.ayush.ftpclient

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.util.Locale

data class SubtitleCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

object InbuiltMediaViewer {

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")
    private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "3gp", "avi", "mov", "flv", "wmv", "ts", "m4v", "ogv")

    fun isImage(file: File): Boolean {
        return IMAGE_EXTENSIONS.contains(file.extension.lowercase())
    }

    fun isVideo(file: File): Boolean {
        return VIDEO_EXTENSIONS.contains(file.extension.lowercase())
    }

    fun openMediaFile(activity: Activity, file: File): Boolean {
        if (!file.exists()) return false

        if (isImage(file)) {
            showImageViewer(activity, file)
            return true
        }

        if (isVideo(file)) {
            showVideoPlayer(activity, file)
            return true
        }

        return false
    }

    fun showImageViewer(activity: Activity, file: File) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_image_viewer, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvImageTitle)
        val tvDetails = dialogView.findViewById<TextView>(R.id.tvImageDetails)
        val ivImage = dialogView.findViewById<ImageView>(R.id.ivInbuiltImage)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btnCloseImageViewer)
        val btnShare = dialogView.findViewById<Button>(R.id.btnShareImage)

        tvTitle.text = file.name

        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            if (bitmap != null) {
                ivImage.setImageBitmap(bitmap)
                val fileSize = HistoryManager.formatFileSize(file.length())
                tvDetails.text = "${bitmap.width} x ${bitmap.height} • $fileSize"
            } else {
                tvDetails.text = HistoryManager.formatFileSize(file.length())
            }
        } catch (e: Exception) {
            tvDetails.text = HistoryManager.formatFileSize(file.length())
        }

        val dialog = AlertDialog.Builder(activity, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        btnClose.setOnClickListener { dialog.dismiss() }

        btnShare.setOnClickListener {
            dialog.dismiss()
            try {
                val uri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    file
                )
                val intent = Intent(Intent.ACTION_SEND).apply {
                    putExtra(Intent.EXTRA_STREAM, uri)
                    type = "image/*"
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(intent, "Share Image"))
            } catch (e: Exception) {
                Toast.makeText(activity, "Error sharing: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        dialog.show()
    }

    private fun showVideoPlayer(activity: Activity, file: File) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_video_player, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvVideoTitle)
        val vvVideo = dialogView.findViewById<VideoView>(R.id.vvInbuiltVideo)
        val tvSubtitles = dialogView.findViewById<TextView>(R.id.tvVideoSubtitles)
        val sbProgress = dialogView.findViewById<SeekBar>(R.id.sbVideoProgress)
        val tvCurrentTime = dialogView.findViewById<TextView>(R.id.tvVideoCurrentTime)
        val tvTotalTime = dialogView.findViewById<TextView>(R.id.tvVideoTotalTime)
        val btnRewind = dialogView.findViewById<Button>(R.id.btnRewind10)
        val btnPlayPause = dialogView.findViewById<ImageButton>(R.id.btnPlayPauseVideo)
        val btnForward = dialogView.findViewById<Button>(R.id.btnForward10)
        val btnSubtitles = dialogView.findViewById<ImageButton>(R.id.btnAddSubtitles)
        val btnFullscreen = dialogView.findViewById<ImageButton>(R.id.btnFullscreenVideo)
        val btnClose = dialogView.findViewById<ImageButton>(R.id.btnCloseVideoPlayer)

        tvTitle.text = file.name
        vvVideo.setVideoPath(file.absolutePath)

        val handler = Handler(Looper.getMainLooper())
        var subtitlesList: List<SubtitleCue> = emptyList()

        fun formatTime(ms: Int): String {
            val totalSec = ms / 1000
            val mins = totalSec / 60
            val secs = totalSec % 60
            return String.format(Locale.US, "%02d:%02d", mins, secs)
        }

        val updateRunnable = object : Runnable {
            override fun run() {
                if (vvVideo.isPlaying) {
                    val cur = vvVideo.currentPosition
                    val dur = vvVideo.duration
                    if (dur > 0) {
                        sbProgress.max = dur
                        sbProgress.progress = cur
                        tvCurrentTime.text = formatTime(cur)
                        tvTotalTime.text = formatTime(dur)
                    }

                    if (subtitlesList.isNotEmpty()) {
                        val cue = subtitlesList.firstOrNull { cur >= it.startMs && cur <= it.endMs }
                        if (cue != null) {
                            tvSubtitles.text = cue.text
                            tvSubtitles.visibility = View.VISIBLE
                        } else {
                            tvSubtitles.visibility = View.GONE
                        }
                    }
                }
                handler.postDelayed(this, 250)
            }
        }

        vvVideo.setOnPreparedListener { mp ->
            mp.isLooping = true
            vvVideo.start()
            btnPlayPause.setImageResource(R.drawable.ic_pause)
            handler.post(updateRunnable)
        }

        btnPlayPause.setOnClickListener {
            if (vvVideo.isPlaying) {
                vvVideo.pause()
                btnPlayPause.setImageResource(R.drawable.ic_play)
            } else {
                vvVideo.start()
                btnPlayPause.setImageResource(R.drawable.ic_pause)
            }
        }

        btnRewind.setOnClickListener {
            val target = (vvVideo.currentPosition - 10000).coerceAtLeast(0)
            vvVideo.seekTo(target)
            sbProgress.progress = target
        }

        btnForward.setOnClickListener {
            val target = (vvVideo.currentPosition + 10000).coerceAtMost(vvVideo.duration)
            vvVideo.seekTo(target)
            sbProgress.progress = target
        }

        sbProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    vvVideo.seekTo(progress)
                    tvCurrentTime.text = formatTime(progress)
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnSubtitles.setOnClickListener {
            showSubtitlePicker(activity) { subFile ->
                subtitlesList = parseSubtitleFile(subFile)
                Toast.makeText(activity, "Loaded subtitles: ${subFile.name}", Toast.LENGTH_SHORT).show()
            }
        }

        btnFullscreen.setOnClickListener {
            Toast.makeText(activity, "Pinch/Expand for Fullscreen playback", Toast.LENGTH_SHORT).show()
        }

        val dialog = AlertDialog.Builder(activity, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        dialog.setOnDismissListener {
            handler.removeCallbacks(updateRunnable)
            vvVideo.stopPlayback()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSubtitlePicker(activity: Activity, onSubtitleSelected: (File) -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_local_file_picker, null)
        val rvLocal = dialogView.findViewById<RecyclerView>(R.id.rvLocalFileList)
        val tvPath = dialogView.findViewById<TextView>(R.id.tvLocalPath)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvLocalEmpty)
        val btnUp = dialogView.findViewById<ImageButton>(R.id.btnLocalUp)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelLocalPicker)

        val rootDir = Environment.getExternalStorageDirectory()
        var currentDir = rootDir

        lateinit var subDialog: AlertDialog
        lateinit var updateList: () -> Unit

        val adapter = LocalFileAdapter(
            onFileClick = { file ->
                if (file.isDirectory) {
                    currentDir = file
                    updateList()
                } else if (file.extension.equals("srt", true) || file.extension.equals("vtt", true)) {
                    subDialog.dismiss()
                    onSubtitleSelected(file)
                } else {
                    Toast.makeText(activity, "Please select an .srt or .vtt subtitle file", Toast.LENGTH_SHORT).show()
                }
            },
            onMoreClick = { _, _ -> }
        )

        rvLocal.layoutManager = LinearLayoutManager(activity)
        rvLocal.adapter = adapter

        updateList = {
            tvPath.text = currentDir.absolutePath
            val items = currentDir.listFiles { file ->
                file.isDirectory || file.extension.equals("srt", true) || file.extension.equals("vtt", true)
            }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()

            adapter.submitList(items)

            if (items.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvLocal.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvLocal.visibility = View.VISIBLE
            }
        }

        updateList()

        subDialog = AlertDialog.Builder(activity, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(subDialog)

        btnUp.setOnClickListener {
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead() && currentDir.absolutePath != rootDir.parentFile?.absolutePath) {
                currentDir = parent
                updateList()
            }
        }

        btnCancel.setOnClickListener { subDialog.dismiss() }
        subDialog.show()
    }

    private fun parseSubtitleFile(file: File): List<SubtitleCue> {
        val cues = mutableListOf<SubtitleCue>()
        try {
            val lines = file.readLines()
            var timeLine: String? = null
            val textBuilder = StringBuilder()

            fun parseTs(ts: String): Long {
                val clean = ts.trim().replace(',', '.')
                val parts = clean.split(":")
                if (parts.size < 3) return 0L
                val hours = parts[0].toLongOrNull() ?: 0L
                val mins = parts[1].toLongOrNull() ?: 0L
                val secParts = parts[2].split(".")
                val secs = secParts[0].toLongOrNull() ?: 0L
                val millis = if (secParts.size > 1) secParts[1].padEnd(3, '0').take(3).toLongOrNull() ?: 0L else 0L
                return (hours * 3600 + mins * 60 + secs) * 1000 + millis
            }

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.contains("-->")) {
                    if (timeLine != null && textBuilder.isNotEmpty()) {
                        val times = timeLine.split("-->")
                        if (times.size == 2) {
                            cues.add(SubtitleCue(parseTs(times[0]), parseTs(times[1]), textBuilder.toString().trim()))
                        }
                        textBuilder.clear()
                    }
                    timeLine = trimmed
                } else if (timeLine != null) {
                    if (trimmed.isEmpty()) {
                        val times = timeLine.split("-->")
                        if (times.size == 2) {
                            cues.add(SubtitleCue(parseTs(times[0]), parseTs(times[1]), textBuilder.toString().trim()))
                        }
                        timeLine = null
                        textBuilder.clear()
                    } else if (!trimmed.all { it.isDigit() }) {
                        if (textBuilder.isNotEmpty()) textBuilder.append("\n")
                        textBuilder.append(trimmed)
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return cues
    }
}
