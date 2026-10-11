package com.ayush.ftpclient

import android.content.Intent
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ayush.ftpclient.databinding.ActivityFtpExplorerBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.io.CopyStreamEvent
import org.apache.commons.net.io.CopyStreamListener
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.Queue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue

class FtpLoginException(msg: String) : IOException(msg)

data class ActiveTransfer(
    val fileName: String,
    val isUpload: Boolean,
    val totalBytes: Long,
    @Volatile var transferredBytes: Long = 0L,
    @Volatile var currentSpeedMbPerSec: Double = 0.0,
    @Volatile var speedText: String = "0.0 MB/s"
)

data class CompletedSessionTransfer(
    val fileName: String,
    val isUpload: Boolean,
    val formattedSize: String,
    val timestamp: String,
    val localFilePath: String = ""
)

private class ActiveDialogHolder(
    val cardActive: View,
    val tvFileName: TextView,
    val pbProgress: ProgressBar,
    val tvBytes: TextView,
    val tvSpeed: TextView,
    val tvNoActive: TextView,
    val rvQueued: RecyclerView,
    val tvNoQueued: TextView,
    val queuedAdapter: QueuedTransferAdapter,
    val rvCompleted: RecyclerView,
    val tvNoCompleted: TextView,
    val sessionAdapter: SessionTransferAdapter
)

class FtpExplorerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityFtpExplorerBinding
    private lateinit var adapter: FtpFileAdapter

    private var host: String = ""
    private var port: Int = 2221
    private var user: String = "anonymous"
    private var pass: String = ""
    private var isAnon: Boolean = false

    private var currentPath: String = "/"
    private val PICK_UPLOAD_FILE_REQUEST = 2002

    private var ftpClient: FTPClient? = null
    @Volatile private var activeTransfer: ActiveTransfer? = null
    @Volatile private var activeDialogHolder: ActiveDialogHolder? = null

    private val downloadQueue: Queue<FTPFile> = ConcurrentLinkedQueue()
    private val sessionCompletedTransfers = mutableListOf<CompletedSessionTransfer>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFtpExplorerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        ThemeManager.applyBackgroundGradient(binding.root, ThemeManager.getSavedTheme(this))

        host = intent.getStringExtra("HOST") ?: ""
        port = intent.getIntExtra("PORT", 2221)
        user = intent.getStringExtra("USER") ?: "anonymous"
        pass = intent.getStringExtra("PASS") ?: ""
        isAnon = intent.getBooleanExtra("ANON", false)

        if (host.isEmpty()) {
            Toast.makeText(this, "Invalid FTP Server Host", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        binding.tvServerAddress.text = "ftp://$host:$port"

        acquireKeepAliveLocks()
        startServerVitalCheck()

        setupRecyclerView()

        binding.btnMenu.setOnClickListener { binding.drawerLayout.openDrawer(GravityCompat.START) }
        binding.btnBack.setOnClickListener { navigateUp() }
        binding.btnRefresh.setOnClickListener { loadDirectory(currentPath) }
        binding.btnClose.setOnClickListener { finish() }
        binding.btnActiveTransfers.setOnClickListener { showActiveTransfersDialog() }
        binding.btnViewToggle.setOnClickListener {
            isGridView = !isGridView
            updateLayoutManager()
        }
        binding.btnUploadToCurrentDir.setOnClickListener { showLocalFilePicker() }

        binding.menuExplorerRoot.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            loadDirectory("/")
        }
        binding.menuExplorerTransfers.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showActiveTransfersDialog()
        }
        binding.menuExplorerHistory.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showHistoryDialog()
        }
        binding.menuExplorerSettings.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showSettingsDialog()
        }
        binding.menuExplorerClose.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            finish()
        }

        loadDirectory("/")
    }

    private var isGridView = false

    private fun setupRecyclerView() {
        adapter = FtpFileAdapter(
            onItemClick = { file ->
                if (file.isDirectory) {
                    val newPath = if (currentPath.endsWith("/")) {
                        "$currentPath${file.name}"
                    } else {
                        "$currentPath/${file.name}"
                    }
                    loadDirectory(newPath)
                } else {
                    previewFtpFile(file)
                }
            },
            onDownloadClick = { file ->
                downloadFile(file)
            },
            onMoreClick = { file ->
                showFtpItemOptionsDialog(file)
            }
        )
        binding.rvFileList.adapter = adapter
        updateLayoutManager()
    }

    private fun updateLayoutManager() {
        if (isGridView) {
            binding.rvFileList.layoutManager = GridLayoutManager(this, 2)
            binding.btnViewToggle.setImageResource(R.drawable.ic_list_view)
        } else {
            binding.rvFileList.layoutManager = LinearLayoutManager(this)
            binding.btnViewToggle.setImageResource(R.drawable.ic_grid_view)
        }
        adapter.isGridView = isGridView
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    @Suppress("DEPRECATION")
    private fun acquireKeepAliveLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FTPApp::ExplorerWakeLock").apply {
                acquire()
            }

            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FTPApp::ExplorerWifiLock").apply {
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private val directoryCache = ConcurrentHashMap<String, List<FTPFile>>()

    private fun prefetchSubfolders(parentPath: String, subfolders: List<FTPFile>) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = getConnectedClient()
                for (sub in subfolders.take(5)) {
                    val subPath = if (parentPath.endsWith("/")) "$parentPath${sub.name}" else "$parentPath/${sub.name}"
                    if (!directoryCache.containsKey(subPath)) {
                        val subFiles = client.listFiles(subPath)?.filter { it.name != "." && it.name != ".." }
                            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
                        directoryCache[subPath] = subFiles
                    }
                }
            } catch (_: Exception) {}
        }
    }

    private val ftpMutex = Mutex()

    private var vitalCheckJob: Job? = null

    private fun startServerVitalCheck() {
        vitalCheckJob?.cancel()
        vitalCheckJob = lifecycleScope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            while (isActive) {
                delay(4000)
                var isAlive = false
                try {
                    val client = ftpClient
                    if (client != null && client.isConnected) {
                        isAlive = client.sendNoOp()
                    } else {
                        val cleanH = host.removePrefix("ftp://").removePrefix("http://").substringBefore(":").substringBefore("/").trim()
                        val socket = Socket()
                        socket.connect(InetSocketAddress(cleanH, port), 2000)
                        isAlive = socket.isConnected
                        socket.close()
                    }
                } catch (_: Exception) {
                    isAlive = false
                }

                if (isAlive) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                }

                if (consecutiveFailures >= 3 && isActive) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FtpExplorerActivity, "🔴 FTP Server disconnected. Closing Explorer...", Toast.LENGTH_LONG).show()
                        finish()
                    }
                    break
                }
            }
        }
    }

    private fun loadDirectory(path: String) {
        currentPath = path
        binding.tvCurrentPath.text = currentPath

        val cachedFiles = directoryCache[path]
        if (!cachedFiles.isNullOrEmpty()) {
            binding.progressBar.visibility = View.GONE
            adapter.submitList(cachedFiles)
            binding.tvEmptyState.visibility = View.GONE
        } else {
            binding.progressBar.visibility = View.VISIBLE
            binding.tvEmptyState.visibility = View.GONE
        }

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val sortedList = ftpMutex.withLock {
                    var client = getConnectedClient()

                    var rawFiles: Array<FTPFile>? = null
                    try {
                        client.changeWorkingDirectory(path)
                        rawFiles = client.listFiles()
                        if (rawFiles == null || rawFiles.isEmpty()) {
                            rawFiles = client.listFiles(path)
                        }
                    } catch (_: Exception) {
                        ftpClient = null
                        client = getConnectedClient()
                        client.changeWorkingDirectory(path)
                        rawFiles = client.listFiles() ?: client.listFiles(path)
                    }

                    val files = rawFiles ?: emptyArray()

                    files.filter { it.name != "." && it.name != ".." }
                        .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                }

                directoryCache[path] = sortedList

                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    binding.tvCurrentPath.text = currentPath
                    adapter.submitList(sortedList)

                    if (sortedList.isEmpty()) {
                        binding.tvEmptyState.visibility = View.VISIBLE
                    } else {
                        binding.tvEmptyState.visibility = View.GONE
                    }
                }

                prefetchSubfolders(path, sortedList.filter { it.isDirectory })
            } catch (e: FtpLoginException) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    showLoginDialog(e.message ?: "Authentication failed")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    binding.progressBar.visibility = View.GONE
                    Toast.makeText(this@FtpExplorerActivity, "Error loading directory: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadFile(file: FTPFile) {
        if (activeTransfer != null) {
            downloadQueue.add(file)
            Toast.makeText(this, "Added ${file.name} to Download Queue", Toast.LENGTH_SHORT).show()
            updateActiveDialogLive()
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            activeTransfer = ActiveTransfer(
                fileName = file.name,
                isUpload = false,
                totalBytes = file.size
            )

            try {
                val client = getConnectedClient()
                client.setFileType(FTP.BINARY_FILE_TYPE)

                val remoteFilePath = if (currentPath.endsWith("/")) "$currentPath${file.name}" else "$currentPath/${file.name}"

                val prefs = getSharedPreferences("FTP_SETTINGS", MODE_PRIVATE)
                val defaultDownloadDirPath = "/storage/emulated/0/Download/FTPApp"
                val downloadDirPath = prefs.getString("PREF_DOWNLOAD_DIR", defaultDownloadDirPath) ?: defaultDownloadDirPath
                val targetDir = File(downloadDirPath)
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val targetFile = File(targetDir, file.name)
                if (targetFile.exists()) {
                    val overwrite = confirmOverwrite(file.name)
                    if (!overwrite) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@FtpExplorerActivity, "Skipped ${file.name}", Toast.LENGTH_SHORT).show()
                        }
                        return@launch
                    }
                }

                val outputStream = FileOutputStream(targetFile)
                val success = client.retrieveFile(remoteFilePath, outputStream)
                outputStream.close()

                withContext(Dispatchers.Main) {
                    if (success) {
                        val formattedSize = HistoryManager.formatFileSize(file.size)
                        HistoryManager.addRecord(
                            context = this@FtpExplorerActivity,
                            type = HistoryManager.TYPE_RECEIVED,
                            fileName = file.name,
                            details = "Downloaded from $host",
                            fileSize = formattedSize,
                            localFilePath = targetFile.absolutePath
                        )

                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        sessionCompletedTransfers.add(
                            0,
                            CompletedSessionTransfer(
                                fileName = file.name,
                                isUpload = false,
                                formattedSize = formattedSize,
                                timestamp = timeStr,
                                localFilePath = targetFile.absolutePath
                            )
                        )

                        Toast.makeText(this@FtpExplorerActivity, "Downloaded: ${targetFile.name}", Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@FtpExplorerActivity, "Download failed", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FtpExplorerActivity, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                activeTransfer = null
                withContext(Dispatchers.Main) {
                    updateButtonProgressLevel(0, 1)
                    updateActiveDialogLive()

                    val nextFile = downloadQueue.poll()
                    if (nextFile != null) {
                        downloadFile(nextFile)
                    }
                }
            }
        }
    }

    private fun showLocalFilePicker() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_local_file_picker, null)
        val rvLocal = dialogView.findViewById<RecyclerView>(R.id.rvLocalFileList)
        val tvPath = dialogView.findViewById<TextView>(R.id.tvLocalPath)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvLocalEmpty)
        val btnUp = dialogView.findViewById<ImageButton>(R.id.btnLocalUp)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelLocalPicker)

        val rootDir = Environment.getExternalStorageDirectory()
        var currentLocalDir = rootDir

        lateinit var dialog: AlertDialog

        lateinit var updateLocalFileList: () -> Unit

        val localAdapter = LocalFileAdapter(
            onFileClick = { clickedFile ->
                if (clickedFile.isDirectory) {
                    currentLocalDir = clickedFile
                    updateLocalFileList()
                } else {
                    dialog.dismiss()
                    uploadLocalFileToCurrentDirectory(clickedFile)
                }
            },
            onMoreClick = { clickedFile, anchorView ->
                val popup = PopupMenu(this, anchorView)
                popup.menu.add(0, 1, 0, "🚀 Upload to FTP")
                popup.setOnMenuItemClickListener {
                    dialog.dismiss()
                    uploadLocalFileToCurrentDirectory(clickedFile)
                    true
                }
                popup.show()
            }
        )

        rvLocal.layoutManager = LinearLayoutManager(this)
        rvLocal.adapter = localAdapter

        updateLocalFileList = {
            tvPath.text = currentLocalDir.absolutePath

            val items = currentLocalDir.listFiles { file -> !file.isHidden }
                ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                ?: emptyList()

            localAdapter.submitList(items)

            if (items.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvLocal.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvLocal.visibility = View.VISIBLE
            }
        }

        updateLocalFileList()

        dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        btnUp.setOnClickListener {
            val parent = currentLocalDir.parentFile
            if (parent != null && parent.canRead() && currentLocalDir.absolutePath != rootDir.parentFile?.absolutePath) {
                currentLocalDir = parent
                updateLocalFileList()
            } else {
                Toast.makeText(this, "Already at top storage directory", Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun uploadLocalFileToCurrentDirectory(file: File) {
        Toast.makeText(this, "Uploading ${file.name} to $currentPath...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            val remoteFileName = file.name
            activeTransfer = ActiveTransfer(
                fileName = remoteFileName,
                isUpload = true,
                totalBytes = file.length()
            )

            try {
                val client = getConnectedClient()
                client.setFileType(FTP.BINARY_FILE_TYPE)

                val remoteFilePath = if (currentPath.endsWith("/")) "$currentPath$remoteFileName" else "$currentPath/$remoteFileName"

                val inputStream = file.inputStream()
                val success = client.storeFile(remoteFilePath, inputStream)
                inputStream.close()

                withContext(Dispatchers.Main) {
                    if (success) {
                        val formattedSize = HistoryManager.formatFileSize(file.length())
                        HistoryManager.addRecord(
                            context = this@FtpExplorerActivity,
                            type = HistoryManager.TYPE_SENT,
                            fileName = remoteFileName,
                            details = "Uploaded to $host ($currentPath)",
                            fileSize = formattedSize,
                            localFilePath = file.absolutePath
                        )

                        val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                        sessionCompletedTransfers.add(
                            0,
                            CompletedSessionTransfer(
                                fileName = remoteFileName,
                                isUpload = true,
                                formattedSize = formattedSize,
                                timestamp = timeStr,
                                localFilePath = file.absolutePath
                            )
                        )

                        Toast.makeText(this@FtpExplorerActivity, "Uploaded ${file.name}!", Toast.LENGTH_SHORT).show()
                        loadDirectory(currentPath)
                    } else {
                        Toast.makeText(this@FtpExplorerActivity, "Upload Failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                if (activeTransfer != null) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@FtpExplorerActivity, "Upload error: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                activeTransfer = null
                withContext(Dispatchers.Main) {
                    updateButtonProgressLevel(0, 1)
                    updateActiveDialogLive()

                    val nextFile = downloadQueue.poll()
                    if (nextFile != null) {
                        downloadFile(nextFile)
                    }
                }
            }
        }
    }

    private fun cancelActiveTransfer() {
        activeTransfer = null
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ftpClient?.abort()
                ftpClient?.disconnect()
                ftpClient = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
            withContext(Dispatchers.Main) {
                updateButtonProgressLevel(0, 1)
                updateActiveDialogLive()
                Toast.makeText(this@FtpExplorerActivity, "Transfer Cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmDeleteFile(item: CompletedSessionTransfer) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
        val tvMsg = dialogView.findViewById<TextView>(R.id.tvConfirmDeleteMsg)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelDelete)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmDelete)

        tvMsg.text = "Are you sure you want to delete '${item.fileName}' from local storage?"

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            if (item.localFilePath.isNotEmpty()) {
                val file = File(item.localFilePath)
                if (file.exists()) {
                    val deleted = file.delete()
                    if (deleted) {
                        Toast.makeText(this, "Deleted ${file.name}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            sessionCompletedTransfers.remove(item)
            updateActiveDialogLive()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun getConnectedClient(): FTPClient {
        var client = ftpClient
        if (client == null || !client.isConnected) {
            client = FTPClient().apply {
                connectTimeout = 7000
                defaultTimeout = 7000
            }

            var lastTimeMs = System.currentTimeMillis()
            var lastBytes = 0L

            client.copyStreamListener = object : CopyStreamListener {
                override fun bytesTransferred(event: CopyStreamEvent?) {}

                override fun bytesTransferred(totalBytesTransferred: Long, bytesTransferred: Int, streamSize: Long) {
                    val transfer = activeTransfer ?: return
                    transfer.transferredBytes = totalBytesTransferred

                    val now = System.currentTimeMillis()
                    val deltaMs = now - lastTimeMs

                    if (deltaMs >= 250) {
                        val deltaBytes = totalBytesTransferred - lastBytes
                        val bytesPerSec = (deltaBytes * 1000.0) / deltaMs
                        val mbPerSec = bytesPerSec / (1024.0 * 1024.0)

                        transfer.currentSpeedMbPerSec = mbPerSec

                        val formattedSpeed = if (mbPerSec >= 1.0) {
                            String.format(Locale.US, "%.1f MB/s", mbPerSec)
                        } else {
                            val kbPerSec = bytesPerSec / 1024.0
                            String.format(Locale.US, "%.0f KB/s", kbPerSec)
                        }

                        transfer.speedText = formattedSpeed

                        lastTimeMs = now
                        lastBytes = totalBytesTransferred

                        runOnUiThread {
                            updateButtonProgressLevel(totalBytesTransferred, transfer.totalBytes)
                            updateActiveDialogLive()
                        }
                    }
                }
            }

            var connected = false
            var lastException: Exception? = null

            val portsToTry = if (port != 21 && port != 2221) {
                listOf(port, 2221, 21)
            } else if (port == 2221) {
                listOf(2221, 21)
            } else {
                listOf(21, 2221)
            }

            for (p in portsToTry) {
                try {
                    client.connect(host, p)
                    if (client.isConnected) {
                        client.connectTimeout = 15000
                        client.defaultTimeout = 60000
                        client.soTimeout = 60000
                        client.setDataTimeout(60000)
                        client.bufferSize = 1024 * 1024
                        client.keepAlive = true
                        client.controlKeepAliveTimeout = 60
                        port = p
                        connected = true
                        break
                    }
                } catch (e: Exception) {
                    lastException = e
                    try { client.disconnect() } catch (_: Exception) {}
                }
            }

            if (!connected || !client.isConnected) {
                val subMsg = if (lastException != null) " (${lastException.message})" else ""
                throw IOException("Unable to reach $host on port ${portsToTry.joinToString("/")}. Check Wi-Fi connection and server IP.$subMsg")
            }

            val loginUser = if (isAnon || user.isEmpty()) "anonymous" else user
            val loginPass = if (isAnon) "" else pass

            var loginSuccess = client.login(loginUser, loginPass)
            if (!loginSuccess && (isAnon || user.isEmpty() || loginUser == "anonymous")) {
                loginSuccess = client.login("anonymous", "anonymous@") || client.login("anonymous", "guest")
            }

            if (!loginSuccess) {
                client.disconnect()
                throw FtpLoginException("FTP Login failed for user '$loginUser'. Check credentials.")
            }

            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)
            ftpClient = client

            HistoryManager.addRecord(
                context = applicationContext,
                type = HistoryManager.TYPE_CONNECTION,
                fileName = "Connected to Remote Server",
                details = "Host: $host:$port"
            )
        }
        return client
    }

    private fun updateButtonProgressLevel(transferred: Long, total: Long) {
        val drawable: Drawable? = binding.btnActiveTransfers.background
        if (drawable != null) {
            val percent = if (total > 0) ((transferred * 100) / total).toInt() else 0
            val level = (percent * 100).coerceIn(0, 10000)
            drawable.setLevel(level)
        }
    }

    private fun updateActiveDialogLive() {
        val holder = activeDialogHolder ?: return
        val transfer = activeTransfer

        if (transfer == null) {
            holder.cardActive.visibility = View.GONE
            holder.tvNoActive.visibility = View.VISIBLE
        } else {
            holder.cardActive.visibility = View.VISIBLE
            holder.tvNoActive.visibility = View.GONE

            holder.tvFileName.text = transfer.fileName
            holder.tvSpeed.text = transfer.speedText

            if (transfer.totalBytes > 0) {
                val progress = ((transfer.transferredBytes * 100) / transfer.totalBytes).toInt()
                holder.pbProgress.isIndeterminate = false
                holder.pbProgress.progress = progress

                val transferredStr = HistoryManager.formatFileSize(transfer.transferredBytes)
                val totalStr = HistoryManager.formatFileSize(transfer.totalBytes)
                holder.tvBytes.text = "$transferredStr / $totalStr ($progress%)"
            } else {
                holder.pbProgress.isIndeterminate = true
                val transferredStr = HistoryManager.formatFileSize(transfer.transferredBytes)
                holder.tvBytes.text = "$transferredStr transferred"
            }
        }

        // Update Queued Downloads List
        val queuedList = downloadQueue.toList()
        holder.queuedAdapter.submitList(queuedList)
        if (queuedList.isEmpty()) {
            holder.tvNoQueued.visibility = View.VISIBLE
            holder.rvQueued.visibility = View.GONE
        } else {
            holder.tvNoQueued.visibility = View.GONE
            holder.rvQueued.visibility = View.VISIBLE
        }

        // Update Completed Session List
        holder.sessionAdapter.submitList(sessionCompletedTransfers)
        if (sessionCompletedTransfers.isEmpty()) {
            holder.tvNoCompleted.visibility = View.VISIBLE
            holder.rvCompleted.visibility = View.GONE
        } else {
            holder.tvNoCompleted.visibility = View.GONE
            holder.rvCompleted.visibility = View.VISIBLE
        }
    }

    private fun showActiveTransfersDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_active_transfers, null)
        val cardActive = dialogView.findViewById<View>(R.id.cardActiveTransfer)
        val tvFileName = dialogView.findViewById<TextView>(R.id.tvTransferFileName)
        val pbProgress = dialogView.findViewById<ProgressBar>(R.id.pbTransferProgress)
        val tvBytes = dialogView.findViewById<TextView>(R.id.tvTransferBytes)
        val tvSpeed = dialogView.findViewById<TextView>(R.id.tvTransferSpeed)
        val btnCancelActive = dialogView.findViewById<Button>(R.id.btnCancelActiveTransfer)
        val tvNoActive = dialogView.findViewById<TextView>(R.id.tvNoActiveTransfers)

        val rvQueued = dialogView.findViewById<RecyclerView>(R.id.rvQueuedDownloads)
        val tvNoQueued = dialogView.findViewById<TextView>(R.id.tvNoQueued)

        val rvCompleted = dialogView.findViewById<RecyclerView>(R.id.rvSessionCompleted)
        val tvNoCompleted = dialogView.findViewById<TextView>(R.id.tvNoCompletedSession)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseTransferDialog)

        val queuedAdapter = QueuedTransferAdapter { queuedFile ->
            downloadQueue.remove(queuedFile)
            updateActiveDialogLive()
            Toast.makeText(this, "Removed ${queuedFile.name} from queue", Toast.LENGTH_SHORT).show()
        }
        rvQueued.layoutManager = LinearLayoutManager(this)
        rvQueued.adapter = queuedAdapter

        val sessionAdapter = SessionTransferAdapter(
            onItemClick = { sessionItem ->
                if (sessionItem.localFilePath.isNotEmpty()) {
                    HistoryManager.openFile(
                        this,
                        HistoryItem(
                            type = if (sessionItem.isUpload) HistoryManager.TYPE_SENT else HistoryManager.TYPE_RECEIVED,
                            fileName = sessionItem.fileName,
                            fileSize = sessionItem.formattedSize,
                            details = "Session Download",
                            timestamp = sessionItem.timestamp,
                            localFilePath = sessionItem.localFilePath
                        )
                    )
                } else {
                    Toast.makeText(this, "File path unavailable", Toast.LENGTH_SHORT).show()
                }
            },
            onDeleteClick = { sessionItem ->
                confirmDeleteFile(sessionItem)
            }
        )

        rvCompleted.layoutManager = LinearLayoutManager(this)
        rvCompleted.adapter = sessionAdapter

        btnCancelActive.setOnClickListener {
            cancelActiveTransfer()
        }

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        val holder = ActiveDialogHolder(
            cardActive = cardActive,
            tvFileName = tvFileName,
            pbProgress = pbProgress,
            tvBytes = tvBytes,
            tvSpeed = tvSpeed,
            tvNoActive = tvNoActive,
            rvQueued = rvQueued,
            tvNoQueued = tvNoQueued,
            queuedAdapter = queuedAdapter,
            rvCompleted = rvCompleted,
            tvNoCompleted = tvNoCompleted,
            sessionAdapter = sessionAdapter
        )

        activeDialogHolder = holder
        updateActiveDialogLive()

        dialog.setOnDismissListener {
            activeDialogHolder = null
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showLoginDialog(errorMessage: String) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ftp_login, null)
        val etUser = dialogView.findViewById<EditText>(R.id.etDialogUser)
        val etPass = dialogView.findViewById<EditText>(R.id.etDialogPass)

        etUser.setText(user)
        etPass.setText(pass)

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle("🔒 FTP Authentication Required")
            .setMessage(errorMessage)
            .setView(dialogView)
            .setPositiveButton("LOGIN") { dialog, _ ->
                user = etUser.text.toString().trim()
                pass = etPass.text.toString().trim()
                isAnon = user.isEmpty() || user == "anonymous"
                ftpClient = null
                dialog.dismiss()
                loadDirectory(currentPath)
            }
            .setNegativeButton("CANCEL") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setCancelable(false)
            .show()
    }

    private fun showHistoryDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_history, null)
        val rvHistory = dialogView.findViewById<RecyclerView>(R.id.rvHistoryList)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvHistoryEmpty)
        val btnFilterAll = dialogView.findViewById<Button>(R.id.btnFilterAll)
        val btnFilterReceived = dialogView.findViewById<Button>(R.id.btnFilterReceived)
        val btnFilterSent = dialogView.findViewById<Button>(R.id.btnFilterSent)
        val btnFilterConn = dialogView.findViewById<Button>(R.id.btnFilterConnections)
        val btnClear = dialogView.findViewById<Button>(R.id.btnClearHistory)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseHistory)

        val historyAdapter = HistoryAdapter { clickedItem ->
            HistoryManager.openFile(this, clickedItem)
        }
        rvHistory.layoutManager = LinearLayoutManager(this)
        rvHistory.adapter = historyAdapter

        val allRecords = HistoryManager.getRecords(this)
        var currentFilter = "ALL"

        fun updateHistoryView() {
            val filteredList = when (currentFilter) {
                HistoryManager.TYPE_RECEIVED -> allRecords.filter { it.type == HistoryManager.TYPE_RECEIVED }
                HistoryManager.TYPE_SENT -> allRecords.filter { it.type == HistoryManager.TYPE_SENT }
                HistoryManager.TYPE_CONNECTION -> allRecords.filter { it.type == HistoryManager.TYPE_CONNECTION }
                else -> allRecords
            }

            historyAdapter.submitList(filteredList)

            if (filteredList.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvHistory.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvHistory.visibility = View.VISIBLE
            }

            btnFilterAll.setBackgroundResource(if (currentFilter == "ALL") R.drawable.glass_btn_bg else R.drawable.glass_input_bg)
            btnFilterReceived.setBackgroundResource(if (currentFilter == HistoryManager.TYPE_RECEIVED) R.drawable.glass_btn_bg else R.drawable.glass_input_bg)
            btnFilterSent.setBackgroundResource(if (currentFilter == HistoryManager.TYPE_SENT) R.drawable.glass_btn_bg else R.drawable.glass_input_bg)
            btnFilterConn.setBackgroundResource(if (currentFilter == HistoryManager.TYPE_CONNECTION) R.drawable.glass_btn_bg else R.drawable.glass_input_bg)
        }

        updateHistoryView()

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        btnFilterAll.setOnClickListener { currentFilter = "ALL"; updateHistoryView() }
        btnFilterReceived.setOnClickListener { currentFilter = HistoryManager.TYPE_RECEIVED; updateHistoryView() }
        btnFilterSent.setOnClickListener { currentFilter = HistoryManager.TYPE_SENT; updateHistoryView() }
        btnFilterConn.setOnClickListener { currentFilter = HistoryManager.TYPE_CONNECTION; updateHistoryView() }

        btnClear.setOnClickListener {
            HistoryManager.clearRecords(this)
            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("FTP_SETTINGS", MODE_PRIVATE)
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)

        val etPort = dialogView.findViewById<EditText>(R.id.etSettingsPort)
        val etDir = dialogView.findViewById<EditText>(R.id.etSettingsDir)
        val etDownloadDir = dialogView.findViewById<EditText>(R.id.etSettingsDownloadDir)
        val etThreads = dialogView.findViewById<EditText>(R.id.etSettingsThreads)
        val etPassive = dialogView.findViewById<EditText>(R.id.etSettingsPassivePorts)

        val btnBrowseDir = dialogView.findViewById<Button>(R.id.btnSelectSettingsDir)
        val btnBrowseDownloadDir = dialogView.findViewById<Button>(R.id.btnSelectSettingsDownloadDir)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelSettings)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveSettings)

        val defaultDownloadDir = "/storage/emulated/0/Download/FTPApp"

        etPort.setText(prefs.getInt("PREF_PORT", 2221).toString())
        etDir.setText(prefs.getString("PREF_DIR", "/storage/emulated/0"))
        etDownloadDir.setText(prefs.getString("PREF_DOWNLOAD_DIR", defaultDownloadDir))
        etThreads.setText(prefs.getInt("PREF_THREADS", 10).toString())
        etPassive.setText(prefs.getString("PREF_PASSIVE", "50000-50100"))

        val openFolderChooserForSharedDir = {
            val currentTypedDir = etDir.text.toString().trim()
            showFolderChooserDialog(currentTypedDir) { selectedPath -> etDir.setText(selectedPath) }
        }

        val openFolderChooserForDownloadDir = {
            val currentTypedDir = etDownloadDir.text.toString().trim()
            showFolderChooserDialog(currentTypedDir) { selectedPath -> etDownloadDir.setText(selectedPath) }
        }

        btnBrowseDir.setOnClickListener { openFolderChooserForSharedDir() }
        etDir.setOnClickListener { openFolderChooserForSharedDir() }

        btnBrowseDownloadDir.setOnClickListener { openFolderChooserForDownloadDir() }
        etDownloadDir.setOnClickListener { openFolderChooserForDownloadDir() }

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val newPort = etPort.text.toString().toIntOrNull() ?: 2221
            val newDir = etDir.text.toString().trim().ifEmpty { "/storage/emulated/0" }
            val newDownloadDir = etDownloadDir.text.toString().trim().ifEmpty { defaultDownloadDir }
            val newThreads = etThreads.text.toString().toIntOrNull() ?: 10
            val newPassive = etPassive.text.toString().trim().ifEmpty { "50000-50100" }

            prefs.edit()
                .putInt("PREF_PORT", newPort)
                .putString("PREF_DIR", newDir)
                .putString("PREF_DOWNLOAD_DIR", newDownloadDir)
                .putInt("PREF_THREADS", newThreads)
                .putString("PREF_PASSIVE", newPassive)
                .apply()

            Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showFolderChooserDialog(initialPath: String, onFolderSelected: (String) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_folder_chooser, null)
        val rvFolders = dialogView.findViewById<RecyclerView>(R.id.rvFolderList)
        val tvPath = dialogView.findViewById<TextView>(R.id.tvFolderPath)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvFolderEmpty)
        val btnUp = dialogView.findViewById<ImageButton>(R.id.btnFolderUp)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelFolder)
        val btnSelect = dialogView.findViewById<Button>(R.id.btnSelectThisFolder)

        val rootDir = Environment.getExternalStorageDirectory()
        var currentDir = File(initialPath.ifEmpty { rootDir.absolutePath })
        if (!currentDir.exists() || !currentDir.isDirectory) {
            currentDir = rootDir
        }

        lateinit var updateFolderList: () -> Unit

        val folderAdapter = FolderAdapter { clickedFolder ->
            currentDir = clickedFolder
            updateFolderList()
        }

        rvFolders.layoutManager = LinearLayoutManager(this)
        rvFolders.adapter = folderAdapter

        updateFolderList = {
            tvPath.text = currentDir.absolutePath

            val subfolders = currentDir.listFiles { file -> file.isDirectory && !file.isHidden }
                ?.sortedBy { it.name.lowercase() } ?: emptyList()

            folderAdapter.submitList(subfolders)

            if (subfolders.isEmpty()) {
                tvEmpty.visibility = View.VISIBLE
                rvFolders.visibility = View.GONE
            } else {
                tvEmpty.visibility = View.GONE
                rvFolders.visibility = View.VISIBLE
            }
        }

        updateFolderList()

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        btnUp.setOnClickListener {
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead() && currentDir.absolutePath != rootDir.parentFile?.absolutePath) {
                currentDir = parent
                updateFolderList()
            } else {
                Toast.makeText(this, "Already at top storage directory", Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSelect.setOnClickListener {
            onFolderSelected(currentDir.absolutePath)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun previewFtpFile(file: FTPFile) {
        val testFile = File(file.name)
        if (!InbuiltMediaViewer.isImage(testFile)) {
            val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
            val tvMsg = dialogView.findViewById<TextView>(R.id.tvConfirmDeleteMsg)
            val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelDelete)
            val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmDelete)

            tvMsg.text = "File '${file.name}' can't be previewed. Do you want to download it permanently instead?"
            btnConfirm.text = "DOWNLOAD"
            btnCancel.text = "CANCEL"

            val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setView(dialogView)
                .create()

            DialogUtils.applyGlassBlur(dialog)

            btnConfirm.setOnClickListener {
                dialog.dismiss()
                downloadFile(file)
            }

            btnCancel.setOnClickListener { dialog.dismiss() }
            dialog.show()
            return
        }

        val prefs = getSharedPreferences("FTP_SETTINGS", MODE_PRIVATE)
        val maxPreviewMb = prefs.getInt("PREF_MAX_PREVIEW_MB", 20)
        val maxPreviewBytes = maxPreviewMb * 1024 * 1024L
        val usableSpace = cacheDir.usableSpace

        if (file.size > maxPreviewBytes || usableSpace < file.size) {
            val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
            val tvMsg = dialogView.findViewById<TextView>(R.id.tvConfirmDeleteMsg)
            val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelDelete)
            val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmDelete)

            val reason = if (file.size > maxPreviewBytes) {
                "File size (${HistoryManager.formatFileSize(file.size)}) exceeds maximum preview limit ($maxPreviewMb MB)."
            } else {
                "Insufficient local cache storage to preview this file."
            }

            tvMsg.text = "$reason Do you want to download it permanently instead?"
            btnConfirm.text = "DOWNLOAD"
            btnCancel.text = "CANCEL"

            val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                .setView(dialogView)
                .create()

            DialogUtils.applyGlassBlur(dialog)

            btnConfirm.setOnClickListener {
                dialog.dismiss()
                downloadFile(file)
            }

            btnCancel.setOnClickListener { dialog.dismiss() }
            dialog.show()
            return
        }

        Toast.makeText(this, "Loading preview for ${file.name}...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = getConnectedClient()
                client.setFileType(FTP.BINARY_FILE_TYPE)
                try { client.changeWorkingDirectory(currentPath) } catch (_: Exception) {}

                val previewDir = File(cacheDir, "ftp_preview_cache")
                if (!previewDir.exists()) previewDir.mkdirs()

                val previewFile = File(previewDir, file.name)

                val outputStream = FileOutputStream(previewFile)
                val success = client.retrieveFile(file.name, outputStream)
                outputStream.flush()
                outputStream.close()

                withContext(Dispatchers.Main) {
                    if (success && previewFile.exists() && previewFile.length() > 0) {
                        InbuiltMediaViewer.showImageViewer(this@FtpExplorerActivity, previewFile)
                    } else {
                        Toast.makeText(this@FtpExplorerActivity, "Preview failed to load", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FtpExplorerActivity, "Preview error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun showFtpItemOptionsDialog(file: FTPFile) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ftp_item_options, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvFtpItemTitle)
        val optRename = dialogView.findViewById<View>(R.id.optFtpRename)
        val optDownload = dialogView.findViewById<View>(R.id.optFtpDownload)
        val optDelete = dialogView.findViewById<View>(R.id.optFtpDelete)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseFtpItemOptions)

        tvTitle.text = file.name

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        optRename.setOnClickListener {
            dialog.dismiss()
            showRemoteRenameDialog(file)
        }

        optDownload.setOnClickListener {
            dialog.dismiss()
            if (file.isDirectory) {
                downloadRemoteFolder(file)
            } else {
                downloadFile(file)
            }
        }

        optDelete.setOnClickListener {
            dialog.dismiss()
            confirmRemoteDelete(file)
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun downloadRemoteFolder(folder: FTPFile) {
        val prefs = getSharedPreferences("FTP_SETTINGS", MODE_PRIVATE)
        val defaultDownloadDir = "/storage/emulated/0/Download/FTPApp"
        val downloadDirPath = prefs.getString("PREF_DOWNLOAD_DIR", defaultDownloadDir) ?: defaultDownloadDir
        val targetLocalFolder = File(downloadDirPath, folder.name)
        if (!targetLocalFolder.exists()) {
            targetLocalFolder.mkdirs()
        }

        Toast.makeText(this, "Downloading folder '${folder.name}'...", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val remoteFolderPath = if (currentPath.endsWith("/")) "$currentPath${folder.name}" else "$currentPath/${folder.name}"
                downloadFolderContentsRecursively(remoteFolderPath, targetLocalFolder)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FtpExplorerActivity, "Finished downloading '${folder.name}'", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FtpExplorerActivity, "Folder download error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun downloadFolderContentsRecursively(remoteDirPath: String, localTargetDir: File) {
        val client = getConnectedClient()
        val files = client.listFiles(remoteDirPath) ?: return

        for (f in files) {
            if (f.name == "." || f.name == "..") continue
            val remoteChildPath = if (remoteDirPath.endsWith("/")) "$remoteDirPath${f.name}" else "$remoteDirPath/${f.name}"
            val localChildFile = File(localTargetDir, f.name)

            if (f.isDirectory) {
                if (!localChildFile.exists()) localChildFile.mkdirs()
                downloadFolderContentsRecursively(remoteChildPath, localChildFile)
            } else {
                if (!localTargetDir.exists()) localTargetDir.mkdirs()
                val outputStream = FileOutputStream(localChildFile)
                client.setFileType(FTP.BINARY_FILE_TYPE)
                client.retrieveFile(remoteChildPath, outputStream)
                outputStream.close()

                val formattedSize = HistoryManager.formatFileSize(f.size)
                HistoryManager.addRecord(
                    context = applicationContext,
                    type = HistoryManager.TYPE_RECEIVED,
                    fileName = f.name,
                    details = "Downloaded from $host",
                    fileSize = formattedSize,
                    localFilePath = localChildFile.absolutePath
                )
            }
        }
    }

    private fun showRemoteRenameDialog(file: FTPFile) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_ftp_login, null)
        val etNewName = dialogView.findViewById<EditText>(R.id.etDialogUser)
        val etPass = dialogView.findViewById<EditText>(R.id.etDialogPass)
        etPass.visibility = View.GONE

        etNewName.hint = "New Name"
        etNewName.setText(file.name)

        AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setTitle("✏ Remote Rename")
            .setView(dialogView)
            .setPositiveButton("RENAME") { d, _ ->
                val newName = etNewName.text.toString().trim()
                if (newName.isNotEmpty() && newName != file.name) {
                    performRemoteRename(file, newName)
                }
                d.dismiss()
            }
            .setNegativeButton("CANCEL") { d, _ -> d.dismiss() }
            .show()
    }

    private fun performRemoteRename(file: FTPFile, newName: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = getConnectedClient()
                val oldPath = if (currentPath.endsWith("/")) "$currentPath${file.name}" else "$currentPath/${file.name}"
                val newPath = if (currentPath.endsWith("/")) "$currentPath$newName" else "$currentPath/$newName"

                val success = client.rename(oldPath, newPath)
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@FtpExplorerActivity, "Renamed to $newName", Toast.LENGTH_SHORT).show()
                        loadDirectory(currentPath)
                    } else {
                        Toast.makeText(this@FtpExplorerActivity, "Rename failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FtpExplorerActivity, "Rename error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun confirmRemoteDelete(file: FTPFile) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
        val tvMsg = dialogView.findViewById<TextView>(R.id.tvConfirmDeleteMsg)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelDelete)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmDelete)

        tvMsg.text = "Are you sure you want to delete '${file.name}' from the remote FTP server?"

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            dialog.dismiss()
            performRemoteDelete(file)
        }

        dialog.show()
    }

    private fun performRemoteDelete(file: FTPFile) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = getConnectedClient()
                val remotePath = if (currentPath.endsWith("/")) "$currentPath${file.name}" else "$currentPath/${file.name}"

                val success = if (file.isDirectory) client.removeDirectory(remotePath) else client.deleteFile(remotePath)
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@FtpExplorerActivity, "Deleted ${file.name}", Toast.LENGTH_SHORT).show()
                        loadDirectory(currentPath)
                    } else {
                        Toast.makeText(this@FtpExplorerActivity, "Delete failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@FtpExplorerActivity, "Delete error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun navigateUp() {
        if (currentPath == "/" || currentPath.isEmpty()) {
            finish()
        } else {
            val parentPath = currentPath.substringBeforeLast('/', "/")
            val nextPath = if (parentPath.isEmpty()) "/" else parentPath
            loadDirectory(nextPath)
        }
    }

    private fun syncRemoteFolderRecursively(folder: FTPFile) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val client = getConnectedClient()
                val folderPath = if (currentPath.endsWith("/")) "$currentPath${folder.name}" else "$currentPath/${folder.name}"
                val files = client.listFiles(folderPath) ?: emptyArray()

                for (f in files) {
                    if (f.isFile) {
                        downloadFile(f)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun clearPreviewCache() {
        try {
            val previewDir = File(cacheDir, "ftp_preview_cache")
            if (previewDir.exists()) {
                previewDir.deleteRecursively()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        vitalCheckJob?.cancel()
        clearPreviewCache()
        sessionCompletedTransfers.clear()
        downloadQueue.clear()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                ftpClient?.disconnect()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun confirmOverwrite(fileName: String): Boolean {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
                val tvMsg = dialogView.findViewById<TextView>(R.id.tvConfirmDeleteMsg)
                val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelDelete)
                val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmDelete)

                tvMsg.text = "File '$fileName' already exists. Do you want to overwrite it?"
                btnConfirm.text = "OVERWRITE"
                btnCancel.text = "SKIP"

                val dialog = AlertDialog.Builder(this@FtpExplorerActivity, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
                    .setView(dialogView)
                    .create()

                DialogUtils.applyGlassBlur(dialog)

                btnConfirm.setOnClickListener {
                    if (continuation.isActive) continuation.resume(true)
                    dialog.dismiss()
                }

                btnCancel.setOnClickListener {
                    if (continuation.isActive) continuation.resume(false)
                    dialog.dismiss()
                }

                dialog.setOnCancelListener {
                    if (continuation.isActive) continuation.resume(false)
                }

                dialog.show()
            }
        }
    }
}
