package com.ayush.ftpclient

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.GravityCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ayush.ftpclient.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var isServerRunning = false
    private lateinit var savedServerAdapter: SavedServerAdapter
    private var realtimePingJob: Job? = null

    private val ftpReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val status = intent?.getStringExtra("STATUS") ?: return
            binding.tvServerStatus.text = status
            if (status.contains("ERROR")) {
                binding.tvServerStatus.setTextColor(0xFFFF007F.toInt())
            } else {
                binding.tvServerStatus.setTextColor(0xFF00FF66.toInt())
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        checkPermissions()

        val filter = IntentFilter("FTP_UPDATE")
        ContextCompat.registerReceiver(this, ftpReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        binding.btnMenu.setOnClickListener {
            binding.drawerLayout.openDrawer(GravityCompat.START)
        }

        binding.btnHomeSyncProgress.setOnClickListener {
            showSyncMonitorDialog()
        }

        binding.menuLocalFileManager.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showLocalFileManagerDialog()
        }

        binding.menuBackup.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showBackupSettingsDialog()
        }

        binding.menuHistory.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showHistoryDialog()
        }

        binding.menuSettings.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showSettingsDialog()
        }

        binding.menuAbout.setOnClickListener {
            binding.drawerLayout.closeDrawer(GravityCompat.START)
            showAboutDialog()
        }

        val prefs = getSharedPreferences("FTP_SETTINGS", MODE_PRIVATE)
        val savedDir = prefs.getString("PREF_DIR", "/storage/emulated/0")
        binding.etSharedDir.setText(savedDir)

        applyCurrentTheme()

        setupSavedServersRecyclerView()

        binding.chkAnonymous.setOnCheckedChangeListener { _, isChecked ->
            binding.etServerUser.isEnabled = !isChecked
            binding.etServerPass.isEnabled = !isChecked

            if (isChecked) {
                binding.etServerUser.error = null
                binding.etServerPass.error = null
            }
        }

        binding.btnToggleServer.setOnClickListener {
            if (isServerRunning) {
                stopService(Intent(this, FtpServerService::class.java))
                binding.btnToggleServer.text = "INITIALIZE SERVER"
                isServerRunning = false
            } else {
                val isAnonymous = binding.chkAnonymous.isChecked
                val user = binding.etServerUser.text.toString().trim()
                val pass = binding.etServerPass.text.toString().trim()
                val sharedDir = binding.etSharedDir.text.toString().trim()

                if (!isAnonymous) {
                    if (user.isEmpty() || pass.isEmpty()) {
                        if (user.isEmpty()) binding.etServerUser.error = "Username required"
                        if (pass.isEmpty()) binding.etServerPass.error = "Password required"

                        Toast.makeText(this, "Enter credentials or select Anonymous Login", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                }

                if (sharedDir.isEmpty()) {
                    binding.etSharedDir.error = "Directory path required"
                    Toast.makeText(this, "Please specify a shared directory", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                binding.etServerUser.error = null
                binding.etServerPass.error = null
                binding.etSharedDir.error = null

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
                    Toast.makeText(this, "Notification permission required to post server notification", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }

                binding.tvServerStatus.text = "STATUS: INITIALIZING..."
                val intent = Intent(this, FtpServerService::class.java).apply {
                    putExtra("USER", user)
                    putExtra("PASS", pass)
                    putExtra("ANON", isAnonymous)
                    putExtra("DIR", sharedDir)
                }
                ContextCompat.startForegroundService(this, intent)
                binding.btnToggleServer.text = "TERMINATE SERVER"
                isServerRunning = true
            }
        }

        binding.btnConnectServer.setOnClickListener {
            val ipInput = binding.etClientIp.text.toString().trim()
            if (ipInput.isEmpty()) {
                binding.etClientIp.error = "Server IP / Address required"
                Toast.makeText(this, "Please enter Server IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.etClientIp.error = null

            var cleanHost = cleanHostAddress(ipInput)
            var port = 2221
            if (ipInput.contains(":")) {
                val parts = ipInput.removePrefix("ftp://").removePrefix("http://").split(":")
                if (parts.size > 1) {
                    port = parts[1].toIntOrNull() ?: 2221
                }
            }

            val isAnonymous = binding.chkAnonymous.isChecked
            val user = if (isAnonymous) "anonymous" else binding.etServerUser.text.toString().trim()
            val pass = if (isAnonymous) "" else binding.etServerPass.text.toString().trim()

            val savedServer = SavedFtpServer(
                id = System.currentTimeMillis().toString(),
                alias = cleanHost,
                host = cleanHost,
                port = port,
                username = user,
                password = pass,
                isAnonymous = isAnonymous
            )
            SavedServerManager.saveServer(this, savedServer)
            updateSavedServersView()

            val intent = Intent(this, FtpExplorerActivity::class.java).apply {
                putExtra("HOST", cleanHost)
                putExtra("PORT", port)
                putExtra("USER", user)
                putExtra("PASS", pass)
                putExtra("ANON", isAnonymous)
            }
            startActivity(intent)
        }
    }

    override fun onResume() {
        super.onResume()
        updateSavedServersView()
        startRealtimeSavedServersPing()
    }

    override fun onPause() {
        super.onPause()
        realtimePingJob?.cancel()
    }

    private fun cleanHostAddress(rawHost: String): String {
        return rawHost.removePrefix("ftp://")
            .removePrefix("http://")
            .substringBefore(":")
            .substringBefore("/")
            .trim()
    }

    private fun testServerOnline(server: SavedFtpServer): Boolean {
        val cleanHost = cleanHostAddress(server.host)
        val localIp = NetworkUtils.getLocalIpAddress()
        val prefs = getSharedPreferences("FTP_SETTINGS", MODE_PRIVATE)
        val localPort = prefs.getInt("PREF_PORT", 2221)

        if (isServerRunning && (cleanHost == localIp || cleanHost == "127.0.0.1" || cleanHost.equals("localhost", true)) && server.port == localPort) {
            return true
        }

        val hostsToTry = mutableListOf(cleanHost)
        if (cleanHost == localIp) {
            hostsToTry.add("127.0.0.1")
            hostsToTry.add("localhost")
        }

        for (h in hostsToTry) {
            if (h.isEmpty()) continue
            try {
                val socket = Socket()
                socket.connect(InetSocketAddress(h, server.port), 1500)
                val connected = socket.isConnected
                socket.close()
                if (connected) return true
            } catch (_: Exception) {}
        }
        return false
    }

    private fun startRealtimeSavedServersPing() {
        realtimePingJob?.cancel()
        realtimePingJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val savedList = SavedServerManager.getSavedServers(this@MainActivity)
                for (server in savedList) {
                    server.isOnline = testServerOnline(server)
                }

                withContext(Dispatchers.Main) {
                    savedServerAdapter.submitList(savedList)
                }

                RealtimeSyncEngine.triggerSyncCheck(this@MainActivity, savedList)
                delay(3000)
            }
        }
    }

    private fun setupSavedServersRecyclerView() {
        savedServerAdapter = SavedServerAdapter(
            onItemClick = { server ->
                val intent = Intent(this, FtpExplorerActivity::class.java).apply {
                    putExtra("HOST", server.host)
                    putExtra("PORT", server.port)
                    putExtra("USER", server.username)
                    putExtra("PASS", server.password)
                    putExtra("ANON", server.isAnonymous)
                }
                startActivity(intent)
            },
            onDeleteClick = { server ->
                confirmDeleteSavedServer(server)
            }
        )
        binding.rvSavedServers.layoutManager = LinearLayoutManager(this)
        binding.rvSavedServers.adapter = savedServerAdapter
    }

    private fun confirmDeleteSavedServer(server: SavedFtpServer) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
        val tvMsg = dialogView.findViewById<TextView>(R.id.tvConfirmDeleteMsg)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelDelete)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmDelete)

        tvMsg.text = "Are you sure you want to remove '${server.alias}' from saved FTP servers?"

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            SavedServerManager.deleteServer(this, server.id)
            updateSavedServersView()
            Toast.makeText(this, "Removed ${server.alias}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun updateSavedServersView() {
        val savedList = SavedServerManager.getSavedServers(this)
        savedServerAdapter.submitList(savedList)

        if (savedList.isEmpty()) {
            binding.tvNoSavedServers.visibility = View.VISIBLE
            binding.rvSavedServers.visibility = View.GONE
        } else {
            binding.tvNoSavedServers.visibility = View.GONE
            binding.rvSavedServers.visibility = View.VISIBLE
        }
    }

    enum class LocalSortMode { NAME, SIZE, DATE }

    private var showHiddenLocalFiles = false
    private var isLocalGridView = false
    private var currentLocalSortMode = LocalSortMode.NAME
    private val selectedTileDetailModes = mutableSetOf<TileDetailMode>(TileDetailMode.SIZE)

    private fun showLocalFileManagerDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_local_file_manager, null)
        val rvLocal = dialogView.findViewById<RecyclerView>(R.id.rvLocalManagerList)
        val tvPath = dialogView.findViewById<TextView>(R.id.tvLocalManagerPath)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvLocalManagerEmpty)
        val btnUp = dialogView.findViewById<ImageButton>(R.id.btnLocalManagerUp)
        val btnSwitchVol = dialogView.findViewById<ImageButton>(R.id.btnSwitchVolume)
        val btnViewToggle = dialogView.findViewById<ImageButton>(R.id.btnLocalViewToggle)
        val btnMenu = dialogView.findViewById<ImageButton>(R.id.btnLocalManagerMenu)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseLocalManager)

        val rootDir = Environment.getExternalStorageDirectory()
        var currentLocalDir = rootDir

        lateinit var dialog: AlertDialog
        lateinit var updateLocalList: () -> Unit
        var previousItemsSignature = ""

        val localAdapter = LocalFileAdapter(
            onFileClick = { clickedFile ->
                if (clickedFile.isDirectory) {
                    currentLocalDir = clickedFile
                    previousItemsSignature = ""
                    updateLocalList()
                } else if (InbuiltMediaViewer.isImage(clickedFile)) {
                    InbuiltMediaViewer.showImageViewer(this, clickedFile)
                } else {
                    HistoryManager.openFile(
                        this,
                        HistoryItem("SENT", clickedFile.name, HistoryManager.formatFileSize(clickedFile.length()), "Local File", "", clickedFile.absolutePath)
                    )
                }
            },
            onMoreClick = { clickedFile, _ ->
                showPerFileActionDialog(clickedFile) {
                    previousItemsSignature = ""
                    updateLocalList()
                }
            }
        )

        rvLocal.adapter = localAdapter

        var currentGridViewState: Boolean? = null

        fun updateLayoutManager() {
            if (currentGridViewState != isLocalGridView || rvLocal.layoutManager == null) {
                currentGridViewState = isLocalGridView
                if (isLocalGridView) {
                    rvLocal.layoutManager = GridLayoutManager(this, 2)
                    btnViewToggle.setImageResource(R.drawable.ic_list_view)
                } else {
                    rvLocal.layoutManager = LinearLayoutManager(this)
                    btnViewToggle.setImageResource(R.drawable.ic_grid_view)
                }
            }
        }

        var isScanningLocal = false

        updateLocalList = {
            if (!isScanningLocal) {
                isScanningLocal = true
                val dirToScan = currentLocalDir
                val showHidden = showHiddenLocalFiles
                val sortMode = currentLocalSortMode
                val tileDetails = selectedTileDetailModes.toSet()

                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        var rawFiles = dirToScan.listFiles { file ->
                            if (showHidden) true else !file.isHidden
                        }?.toList() ?: emptyList()

                        rawFiles = when (sortMode) {
                            LocalSortMode.NAME -> rawFiles.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
                            LocalSortMode.SIZE -> rawFiles.sortedWith(compareBy({ !it.isDirectory }, { -it.length() }))
                            LocalSortMode.DATE -> rawFiles.sortedWith(compareBy({ !it.isDirectory }, { -it.lastModified() }))
                        }

                        val quickSignature = "${dirToScan.absolutePath}_${rawFiles.size}_${if (rawFiles.isNotEmpty()) rawFiles.first().lastModified() else 0}"

                        withContext(Dispatchers.Main) {
                            tvPath.text = dirToScan.absolutePath
                            updateLayoutManager()
                            localAdapter.selectedDetailModes.clear()
                            localAdapter.selectedDetailModes.addAll(tileDetails)

                            if (quickSignature != previousItemsSignature) {
                                previousItemsSignature = quickSignature
                                val layoutState = rvLocal.layoutManager?.onSaveInstanceState()
                                localAdapter.submitList(rawFiles)
                                if (layoutState != null) {
                                    rvLocal.layoutManager?.onRestoreInstanceState(layoutState)
                                }
                            }

                            if (rawFiles.isEmpty()) {
                                tvEmpty.visibility = View.VISIBLE
                                rvLocal.visibility = View.GONE
                            } else {
                                tvEmpty.visibility = View.GONE
                                rvLocal.visibility = View.VISIBLE
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    } finally {
                        isScanningLocal = false
                    }
                }
            }
        }

        updateLocalList()

        btnViewToggle.setOnClickListener {
            isLocalGridView = !isLocalGridView
            updateLocalList()
        }

        btnSwitchVol.setOnClickListener {
            showStorageVolumeChooserDialog { selectedVolume ->
                currentLocalDir = selectedVolume
                previousItemsSignature = ""
                updateLocalList()
            }
        }

        btnMenu.setOnClickListener {
            showTopManagerOptionsDialog {
                previousItemsSignature = ""
                updateLocalList()
            }
        }

        var localPollJob: Job? = null
        fun startLocalPoll() {
            localPollJob?.cancel()
            localPollJob = lifecycleScope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(1500)
                    withContext(Dispatchers.Main) {
                        updateLocalList()
                    }
                }
            }
        }

        startLocalPoll()

        dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        dialog.setOnDismissListener {
            localPollJob?.cancel()
        }

        btnUp.setOnClickListener {
            val parent = currentLocalDir.parentFile
            if (parent != null && parent.canRead() && currentLocalDir.absolutePath != rootDir.parentFile?.absolutePath) {
                currentLocalDir = parent
                previousItemsSignature = ""
                updateLocalList()
            } else {
                Toast.makeText(this, "Already at top storage directory", Toast.LENGTH_SHORT).show()
            }
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showSyncMonitorDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_sync_monitor, null)
        val cardProgress = dialogView.findViewById<View>(R.id.cardSyncProgress)
        val tvAlias = dialogView.findViewById<TextView>(R.id.tvSyncServerAlias)
        val pbProgress = dialogView.findViewById<ProgressBar>(R.id.pbSyncProgress)
        val tvBytes = dialogView.findViewById<TextView>(R.id.tvSyncBytesText)
        val tvSpeed = dialogView.findViewById<TextView>(R.id.tvSyncSpeedText)
        val tvRemaining = dialogView.findViewById<TextView>(R.id.tvSyncRemainingText)
        val tvNoSync = dialogView.findViewById<TextView>(R.id.tvNoActiveSync)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseSyncMonitor)

        fun updateSyncMonitorUI() {
            if (RealtimeSyncEngine.isSyncing) {
                cardProgress.visibility = View.VISIBLE
                tvNoSync.visibility = View.GONE

                tvAlias.text = "Active Sync: ${RealtimeSyncEngine.activeServerAlias}"
                tvSpeed.text = RealtimeSyncEngine.currentSpeedText

                val syncedStr = HistoryManager.formatFileSize(RealtimeSyncEngine.syncedBytesTotal)
                val remainStr = HistoryManager.formatFileSize(RealtimeSyncEngine.remainingBytesTotal)

                tvBytes.text = "$syncedStr Synced"
                tvRemaining.text = "$remainStr Remaining to Sync"

                val total = RealtimeSyncEngine.syncedBytesTotal + RealtimeSyncEngine.remainingBytesTotal
                if (total > 0) {
                    val pct = ((RealtimeSyncEngine.syncedBytesTotal * 100) / total).toInt()
                    pbProgress.isIndeterminate = false
                    pbProgress.progress = pct
                } else {
                    pbProgress.isIndeterminate = true
                }
            } else {
                cardProgress.visibility = View.GONE
                tvNoSync.visibility = View.VISIBLE
            }
        }

        var monitorPollJob: Job? = null
        fun startMonitorPoll() {
            monitorPollJob?.cancel()
            monitorPollJob = lifecycleScope.launch(Dispatchers.IO) {
                while (isActive) {
                    withContext(Dispatchers.Main) {
                        updateSyncMonitorUI()
                    }
                    delay(300)
                }
            }
        }

        startMonitorPoll()

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        dialog.setOnDismissListener {
            monitorPollJob?.cancel()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showStorageVolumeChooserDialog(onVolumeSelected: (File) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_storage_volume, null)
        val rvVolumes = dialogView.findViewById<RecyclerView>(R.id.rvStorageVolumesList)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseStorageVolumes)

        val volumes = StorageUtils.getStorageVolumes(this)

        lateinit var dialog: AlertDialog

        val adapter = StorageVolumeAdapter { selectedVolume ->
            onVolumeSelected(selectedVolume)
            dialog.dismiss()
        }

        rvVolumes.layoutManager = LinearLayoutManager(this)
        rvVolumes.adapter = adapter
        adapter.submitList(volumes)

        dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showPerFileActionDialog(file: File, onFileUpdated: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_file_options, null)
        val tvName = dialogView.findViewById<TextView>(R.id.tvOptionFileName)
        val optTransmit = dialogView.findViewById<View>(R.id.optTransmitFtp)
        val optCopy = dialogView.findViewById<View>(R.id.optCopyFile)
        val optMove = dialogView.findViewById<View>(R.id.optMoveFile)
        val optShare = dialogView.findViewById<View>(R.id.optShareExternal)
        val optCompress = dialogView.findViewById<View>(R.id.optCompressZip)
        val optDetails = dialogView.findViewById<View>(R.id.optFileDetails)
        val optDelete = dialogView.findViewById<View>(R.id.optDeleteFile)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseFileOptions)

        tvName.text = file.name

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        optTransmit.setOnClickListener {
            dialog.dismiss()
            showShareToServerDialog(file)
        }

        optCopy.setOnClickListener {
            dialog.dismiss()
            showFolderChooserDialog(file.parent ?: "/storage/emulated/0") { targetFolderPath ->
                copyFileToFolder(file, File(targetFolderPath), onFileUpdated)
            }
        }

        optMove.setOnClickListener {
            dialog.dismiss()
            showFolderChooserDialog(file.parent ?: "/storage/emulated/0") { targetFolderPath ->
                moveFileToFolder(file, File(targetFolderPath), onFileUpdated)
            }
        }

        optShare.setOnClickListener {
            dialog.dismiss()
            shareFileExternal(file)
        }

        optCompress.setOnClickListener {
            dialog.dismiss()
            compressFileToZip(file, onFileUpdated)
        }

        optDetails.setOnClickListener {
            dialog.dismiss()
            showFileDetailsDialog(file)
        }

        optDelete.setOnClickListener {
            dialog.dismiss()
            confirmDeleteLocalFile(file, onFileUpdated)
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun copyFileToFolder(source: File, targetDir: File, onComplete: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val destFile = File(targetDir, source.name)
                source.copyTo(destFile, overwrite = true)
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Copied ${source.name} to ${targetDir.name}", Toast.LENGTH_SHORT).show()
                    onComplete()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Copy failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun moveFileToFolder(source: File, targetDir: File, onComplete: () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val destFile = File(targetDir, source.name)
                val success = source.renameTo(destFile) || run {
                    source.copyTo(destFile, overwrite = true)
                    source.delete()
                }
                withContext(Dispatchers.Main) {
                    if (success) {
                        Toast.makeText(this@MainActivity, "Moved ${source.name} to ${targetDir.name}", Toast.LENGTH_SHORT).show()
                        onComplete()
                    } else {
                        Toast.makeText(this@MainActivity, "Move failed", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "Move failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun shareFileExternal(file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "$packageName.fileprovider",
                file
            )
            val mimeType = contentResolver.getType(uri) ?: "*/*"
            val intent = Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uri)
                type = mimeType
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share File"))
        } catch (e: Exception) {
            Toast.makeText(this, "Error sharing file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun compressFileToZip(file: File, onComplete: () -> Unit) {
        Toast.makeText(this, "Compressing ${file.name}...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            StorageUtils.compressToZip(file) { zipFile ->
                runOnUiThread {
                    if (zipFile != null && zipFile.exists()) {
                        Toast.makeText(this@MainActivity, "Created ${zipFile.name}", Toast.LENGTH_LONG).show()
                        onComplete()
                    } else {
                        Toast.makeText(this@MainActivity, "Compression failed", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun showTopManagerOptionsDialog(onStateChanged: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_manager_options, null)
        val chkHidden = dialogView.findViewById<CheckBox>(R.id.chkShowHiddenFiles)
        val optSortName = dialogView.findViewById<View>(R.id.optSortName)
        val optSortSize = dialogView.findViewById<View>(R.id.optSortSize)
        val optSortDate = dialogView.findViewById<View>(R.id.optSortDate)
        val optDetailSize = dialogView.findViewById<View>(R.id.optDetailSize)
        val optDetailDate = dialogView.findViewById<View>(R.id.optDetailDate)
        val optDetailPath = dialogView.findViewById<View>(R.id.optDetailPath)
        val optDetailNone = dialogView.findViewById<View>(R.id.optDetailNone)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseManagerOptions)

        chkHidden.isChecked = showHiddenLocalFiles
        chkHidden.setOnCheckedChangeListener { _, isChecked ->
            showHiddenLocalFiles = isChecked
            onStateChanged()
        }

        val activeBg = R.drawable.glass_btn_bg
        val inactiveBg = R.drawable.glass_input_bg

        fun refreshTileBgs() {
            optSortName.setBackgroundResource(if (currentLocalSortMode == LocalSortMode.NAME) activeBg else inactiveBg)
            optSortSize.setBackgroundResource(if (currentLocalSortMode == LocalSortMode.SIZE) activeBg else inactiveBg)
            optSortDate.setBackgroundResource(if (currentLocalSortMode == LocalSortMode.DATE) activeBg else inactiveBg)

            optDetailSize.setBackgroundResource(if (selectedTileDetailModes.contains(TileDetailMode.SIZE)) activeBg else inactiveBg)
            optDetailDate.setBackgroundResource(if (selectedTileDetailModes.contains(TileDetailMode.DATE)) activeBg else inactiveBg)
            optDetailPath.setBackgroundResource(if (selectedTileDetailModes.contains(TileDetailMode.DIRECTORY)) activeBg else inactiveBg)
            optDetailNone.setBackgroundResource(if (selectedTileDetailModes.isEmpty()) activeBg else inactiveBg)
        }

        refreshTileBgs()

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        optSortName.setOnClickListener {
            currentLocalSortMode = LocalSortMode.NAME
            refreshTileBgs()
            onStateChanged()
        }

        optSortSize.setOnClickListener {
            currentLocalSortMode = LocalSortMode.SIZE
            refreshTileBgs()
            onStateChanged()
        }

        optSortDate.setOnClickListener {
            currentLocalSortMode = LocalSortMode.DATE
            refreshTileBgs()
            onStateChanged()
        }

        optDetailSize.setOnClickListener {
            if (selectedTileDetailModes.contains(TileDetailMode.SIZE)) {
                selectedTileDetailModes.remove(TileDetailMode.SIZE)
            } else {
                selectedTileDetailModes.add(TileDetailMode.SIZE)
            }
            refreshTileBgs()
            onStateChanged()
        }

        optDetailDate.setOnClickListener {
            if (selectedTileDetailModes.contains(TileDetailMode.DATE)) {
                selectedTileDetailModes.remove(TileDetailMode.DATE)
            } else {
                selectedTileDetailModes.add(TileDetailMode.DATE)
            }
            refreshTileBgs()
            onStateChanged()
        }

        optDetailPath.setOnClickListener {
            if (selectedTileDetailModes.contains(TileDetailMode.DIRECTORY)) {
                selectedTileDetailModes.remove(TileDetailMode.DIRECTORY)
            } else {
                selectedTileDetailModes.add(TileDetailMode.DIRECTORY)
            }
            refreshTileBgs()
            onStateChanged()
        }

        optDetailNone.setOnClickListener {
            selectedTileDetailModes.clear()
            refreshTileBgs()
            onStateChanged()
        }

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showFileDetailsDialog(file: File) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_file_details, null)
        val tvName = dialogView.findViewById<TextView>(R.id.tvDetailName)
        val tvType = dialogView.findViewById<TextView>(R.id.tvDetailType)
        val tvSize = dialogView.findViewById<TextView>(R.id.tvDetailSize)
        val tvDate = dialogView.findViewById<TextView>(R.id.tvDetailDate)
        val tvPath = dialogView.findViewById<TextView>(R.id.tvDetailPath)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseDetails)

        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(file.lastModified()))
        val sizeStr = if (file.isDirectory) "Directory" else HistoryManager.formatFileSize(file.length())

        tvName.text = "• Name: ${file.name}"
        tvType.text = "• Type: ${if (file.isDirectory) "Folder" else "File"}"
        tvSize.text = "• Size: $sizeStr"
        tvDate.text = "• Modified: $dateStr"
        tvPath.text = "• Path: ${file.absolutePath}"

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun confirmDeleteLocalFile(file: File, onDeleted: () -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_confirm_delete, null)
        val tvMsg = dialogView.findViewById<TextView>(R.id.tvConfirmDeleteMsg)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelDelete)
        val btnConfirm = dialogView.findViewById<Button>(R.id.btnConfirmDelete)

        tvMsg.text = "Are you sure you want to delete '${file.name}' from local storage?"

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            if (file.exists() && file.deleteRecursively()) {
                Toast.makeText(this, "Deleted ${file.name}", Toast.LENGTH_SHORT).show()
                onDeleted()
            } else {
                Toast.makeText(this, "Failed to delete ${file.name}", Toast.LENGTH_SHORT).show()
            }
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showShareToServerDialog(file: File) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_share_to_server, null)
        val tvFileName = dialogView.findViewById<TextView>(R.id.tvSharingFileName)
        val rvServers = dialogView.findViewById<RecyclerView>(R.id.rvTargetServersList)
        val tvNoServers = dialogView.findViewById<TextView>(R.id.tvNoTargetServers)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelShare)

        tvFileName.text = "${file.name} (${HistoryManager.formatFileSize(file.length())})"

        lateinit var shareDialog: AlertDialog

        val shareAdapter = SavedServerAdapter(
            onItemClick = { targetServer ->
                shareDialog.dismiss()
                val intent = Intent(this, FtpExplorerActivity::class.java).apply {
                    putExtra("HOST", targetServer.host)
                    putExtra("PORT", targetServer.port)
                    putExtra("USER", targetServer.username)
                    putExtra("PASS", targetServer.password)
                    putExtra("ANON", targetServer.isAnonymous)
                }
                startActivity(intent)
                Toast.makeText(this, "Connecting to ${targetServer.alias}...", Toast.LENGTH_SHORT).show()
            },
            onDeleteClick = { targetServer ->
                confirmDeleteSavedServer(targetServer)
            }
        )

        rvServers.layoutManager = LinearLayoutManager(this)
        rvServers.adapter = shareAdapter

        val savedList = SavedServerManager.getSavedServers(this)
        shareAdapter.submitList(savedList)

        if (savedList.isEmpty()) {
            tvNoServers.visibility = View.VISIBLE
            rvServers.visibility = View.GONE
        } else {
            tvNoServers.visibility = View.GONE
            rvServers.visibility = View.VISIBLE
        }

        lifecycleScope.launch(Dispatchers.IO) {
            for (server in savedList) {
                server.isOnline = testServerOnline(server)
            }
            withContext(Dispatchers.Main) {
                shareAdapter.notifyDataSetChanged()
            }
        }

        shareDialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(shareDialog)

        btnCancel.setOnClickListener { shareDialog.dismiss() }
        shareDialog.show()
    }

    private fun showBackupSettingsDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_backup_settings, null)
        val rvServers = dialogView.findViewById<RecyclerView>(R.id.rvBackupServersList)
        val tvNoServers = dialogView.findViewById<TextView>(R.id.tvNoBackupServers)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseBackupSettings)

        lateinit var dialog: AlertDialog

        val adapter = SavedServerAdapter(
            onItemClick = { savedServer ->
                dialog.dismiss()
                showBackupProfileDialog(savedServer)
            },
            onDeleteClick = { savedServer ->
                confirmDeleteSavedServer(savedServer)
            }
        )

        rvServers.layoutManager = LinearLayoutManager(this)
        rvServers.adapter = adapter

        val savedList = SavedServerManager.getSavedServers(this)
        adapter.submitList(savedList)

        if (savedList.isEmpty()) {
            tvNoServers.visibility = View.VISIBLE
            rvServers.visibility = View.GONE
        } else {
            tvNoServers.visibility = View.GONE
            rvServers.visibility = View.VISIBLE
        }

        lifecycleScope.launch(Dispatchers.IO) {
            for (server in savedList) {
                server.isOnline = testServerOnline(server)
            }
            withContext(Dispatchers.Main) {
                adapter.notifyDataSetChanged()
            }
        }

        dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showBackupProfileDialog(server: SavedFtpServer) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_backup_profile, null)
        val tvTitle = dialogView.findViewById<TextView>(R.id.tvBackupProfileTitle)
        val etFolder = dialogView.findViewById<EditText>(R.id.etBackupFolderPath)
        val etRemoteFolder = dialogView.findViewById<EditText>(R.id.etRemoteBackupFolderPath)
        val btnBrowse = dialogView.findViewById<Button>(R.id.btnBrowseBackupFolder)
        val btnBrowseRemote = dialogView.findViewById<Button>(R.id.btnBrowseRemoteFolder)
        val chkAuto = dialogView.findViewById<CheckBox>(R.id.chkAutoBackup)
        val spnFreq = dialogView.findViewById<Spinner>(R.id.spnBackupFrequency)
        val etCustomMin = dialogView.findViewById<EditText>(R.id.etCustomMinutes)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelBackupProfile)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveBackupProfile)

        tvTitle.text = "REALTIME SYNC: ${server.alias}"

        val frequencies = arrayOf("Realtime Sync", "Every 15 Mins", "Every 1 Hour", "Daily", "Custom (Mins)")
        val spinnerAdapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, frequencies)
        spnFreq.adapter = spinnerAdapter

        val profile = BackupManager.getProfileForServer(this, server.id, server.alias)
        etFolder.setText(profile.localFolderPath)
        etRemoteFolder.setText(profile.remoteFolderPath)
        chkAuto.isChecked = profile.autoBackupEnabled
        etCustomMin.setText(profile.customMinutes.toString())

        val index = frequencies.indexOf(profile.frequency).coerceAtLeast(0)
        spnFreq.setSelection(index)

        spnFreq.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (position == 4) {
                    etCustomMin.visibility = View.VISIBLE
                } else {
                    etCustomMin.visibility = View.GONE
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        lateinit var dialog: AlertDialog

        val openFolderChooser = {
            val currentTypedDir = etFolder.text.toString().trim()
            showFolderChooserDialog(currentTypedDir) { selectedPath ->
                etFolder.setText(selectedPath)
            }
        }

        btnBrowse.setOnClickListener { openFolderChooser() }
        etFolder.setOnClickListener { openFolderChooser() }

        btnBrowseRemote?.setOnClickListener {
            showRemoteFolderChooserDialog(server) { remotePath ->
                etRemoteFolder.setText(remotePath)
            }
        }

        dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val newFolder = etFolder.text.toString().trim().ifEmpty { "/storage/emulated/0/Download/FTPApp" }
            val newRemoteFolder = etRemoteFolder.text.toString().trim().ifEmpty { "/" }
            val newAuto = chkAuto.isChecked
            val newFreq = frequencies[spnFreq.selectedItemPosition]
            val newCustomMin = etCustomMin.text.toString().toIntOrNull() ?: 15

            val newProfile = BackupProfile(
                serverId = server.id,
                serverAlias = server.alias,
                localFolderPath = newFolder,
                remoteFolderPath = newRemoteFolder,
                autoBackupEnabled = newAuto,
                frequency = newFreq,
                customMinutes = newCustomMin
            )

            BackupManager.saveProfile(this, newProfile)
            Toast.makeText(this, "Realtime sync profile saved for ${server.alias}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showRemoteFolderChooserDialog(server: SavedFtpServer, onRemoteFolderSelected: (String) -> Unit) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_folder_chooser, null)
        val rvFolders = dialogView.findViewById<RecyclerView>(R.id.rvFolderList)
        val tvPath = dialogView.findViewById<TextView>(R.id.tvFolderPath)
        val tvEmpty = dialogView.findViewById<TextView>(R.id.tvFolderEmpty)
        val btnUp = dialogView.findViewById<ImageButton>(R.id.btnFolderUp)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelFolder)
        val btnSelect = dialogView.findViewById<Button>(R.id.btnSelectThisFolder)

        val titleHeader = dialogView.findViewById<TextView>(R.id.tvFolderChooserTitle)
        titleHeader?.text = "SELECT REMOTE TARGET FOLDER"

        var currentRemotePath = "/"
        lateinit var dialog: AlertDialog
        lateinit var updateRemoteFolderList: () -> Unit

        var ftpClient: FTPClient? = null

        val folderAdapter = FolderAdapter { clickedFolder ->
            val newPath = if (currentRemotePath.endsWith("/")) "$currentRemotePath${clickedFolder.name}" else "$currentRemotePath/${clickedFolder.name}"
            currentRemotePath = newPath
            updateRemoteFolderList()
        }

        rvFolders.layoutManager = LinearLayoutManager(this)
        rvFolders.adapter = folderAdapter

        updateRemoteFolderList = {
            tvPath.text = currentRemotePath
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    var client = ftpClient
                    if (client == null || !client.isConnected) {
                        client = FTPClient().apply {
                            connectTimeout = 3000
                            defaultTimeout = 3000
                        }
                        val cleanH = cleanHostAddress(server.host)
                        client.connect(cleanH, server.port)
                        val user = if (server.isAnonymous) "anonymous" else server.username
                        val pass = if (server.isAnonymous) "" else server.password
                        client.login(user, pass)
                        client.enterLocalPassiveMode()
                        ftpClient = client
                    }

                    val files = client.listFiles(currentRemotePath) ?: emptyArray()
                    val remoteSubfolders = files.filter { it.isDirectory && it.name != "." && it.name != ".." }
                        .map { File(it.name) }
                        .sortedBy { it.name.lowercase() }

                    withContext(Dispatchers.Main) {
                        folderAdapter.submitList(remoteSubfolders)
                        if (remoteSubfolders.isEmpty()) {
                            tvEmpty.visibility = View.VISIBLE
                            rvFolders.visibility = View.GONE
                        } else {
                            tvEmpty.visibility = View.GONE
                            rvFolders.visibility = View.VISIBLE
                        }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(this@MainActivity, "FTP Error: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }

        updateRemoteFolderList()

        dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        btnUp.setOnClickListener {
            if (currentRemotePath != "/" && currentRemotePath.isNotEmpty()) {
                val parent = currentRemotePath.substringBeforeLast('/', "/")
                currentRemotePath = if (parent.isEmpty()) "/" else parent
                updateRemoteFolderList()
            } else {
                Toast.makeText(this, "Already at root directory", Toast.LENGTH_SHORT).show()
            }
        }

        btnCancel.setOnClickListener {
            lifecycleScope.launch(Dispatchers.IO) { try { ftpClient?.disconnect() } catch (_: Exception) {} }
            dialog.dismiss()
        }

        btnSelect.setOnClickListener {
            onRemoteFolderSelected(currentRemotePath)
            lifecycleScope.launch(Dispatchers.IO) { try { ftpClient?.disconnect() } catch (_: Exception) {} }
            dialog.dismiss()
        }

        dialog.show()
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

        DialogUtils.applyGlassBlur(dialog)

        btnFilterAll.setOnClickListener {
            currentFilter = "ALL"
            updateHistoryView()
        }

        btnFilterReceived.setOnClickListener {
            currentFilter = HistoryManager.TYPE_RECEIVED
            updateHistoryView()
        }

        btnFilterSent.setOnClickListener {
            currentFilter = HistoryManager.TYPE_SENT
            updateHistoryView()
        }

        btnFilterConn.setOnClickListener {
            currentFilter = HistoryManager.TYPE_CONNECTION
            updateHistoryView()
        }

        btnClear.setOnClickListener {
            HistoryManager.clearRecords(this)
            Toast.makeText(this, "History cleared", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun applyCurrentTheme() {
        val currentTheme = ThemeManager.getSavedTheme(this)
        ThemeManager.applyThemeToMainActivity(binding, currentTheme)
    }

    private fun showSettingsDialog() {
        val prefs = getSharedPreferences("FTP_SETTINGS", MODE_PRIVATE)
        val dialogView = layoutInflater.inflate(R.layout.dialog_settings, null)

        val spnTheme = dialogView.findViewById<Spinner>(R.id.spnSettingsTheme)
        val etMaxPreview = dialogView.findViewById<EditText>(R.id.etSettingsMaxPreviewMb)
        val etPort = dialogView.findViewById<EditText>(R.id.etSettingsPort)
        val etDir = dialogView.findViewById<EditText>(R.id.etSettingsDir)
        val etDownloadDir = dialogView.findViewById<EditText>(R.id.etSettingsDownloadDir)
        val etThreads = dialogView.findViewById<EditText>(R.id.etSettingsThreads)
        val etPassive = dialogView.findViewById<EditText>(R.id.etSettingsPassivePorts)

        val btnBrowseDir = dialogView.findViewById<Button>(R.id.btnSelectSettingsDir)
        val btnBrowseDownloadDir = dialogView.findViewById<Button>(R.id.btnSelectSettingsDownloadDir)
        val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelSettings)
        val btnSave = dialogView.findViewById<Button>(R.id.btnSaveSettings)

        val themesList = AppTheme.entries.map { it.displayName }
        val themeSpinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, themesList)
        spnTheme.adapter = themeSpinnerAdapter

        val savedTheme = ThemeManager.getSavedTheme(this)
        val currentThemeIndex = AppTheme.entries.indexOf(savedTheme).coerceAtLeast(0)
        spnTheme.setSelection(currentThemeIndex)

        val defaultDownloadDir = "/storage/emulated/0/Download/FTPApp"

        etMaxPreview.setText(prefs.getInt("PREF_MAX_PREVIEW_MB", 20).toString())
        etPort.setText(prefs.getInt("PREF_PORT", 2221).toString())
        etDir.setText(prefs.getString("PREF_DIR", "/storage/emulated/0"))
        etDownloadDir.setText(prefs.getString("PREF_DOWNLOAD_DIR", defaultDownloadDir))
        etThreads.setText(prefs.getInt("PREF_THREADS", 10).toString())
        etPassive.setText(prefs.getString("PREF_PASSIVE", "50000-50100"))

        val openFolderChooserForSharedDir = {
            val currentTypedDir = etDir.text.toString().trim()
            showFolderChooserDialog(currentTypedDir) { selectedPath ->
                etDir.setText(selectedPath)
            }
        }

        val openFolderChooserForDownloadDir = {
            val currentTypedDir = etDownloadDir.text.toString().trim()
            showFolderChooserDialog(currentTypedDir) { selectedPath ->
                etDownloadDir.setText(selectedPath)
            }
        }

        btnBrowseDir.setOnClickListener { openFolderChooserForSharedDir() }
        etDir.setOnClickListener { openFolderChooserForSharedDir() }

        btnBrowseDownloadDir.setOnClickListener { openFolderChooserForDownloadDir() }
        etDownloadDir.setOnClickListener { openFolderChooserForDownloadDir() }

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val selectedTheme = AppTheme.entries[spnTheme.selectedItemPosition]
            ThemeManager.saveTheme(this, selectedTheme)
            applyCurrentTheme()

            val newMaxPreview = etMaxPreview.text.toString().toIntOrNull() ?: 20
            val newPort = etPort.text.toString().toIntOrNull() ?: 2221
            val newDir = etDir.text.toString().trim().ifEmpty { "/storage/emulated/0" }
            val newDownloadDir = etDownloadDir.text.toString().trim().ifEmpty { defaultDownloadDir }
            val newThreads = etThreads.text.toString().toIntOrNull() ?: 10
            val newPassive = etPassive.text.toString().trim().ifEmpty { "50000-50100" }

            prefs.edit()
                .putInt("PREF_MAX_PREVIEW_MB", newMaxPreview)
                .putInt("PREF_PORT", newPort)
                .putString("PREF_DIR", newDir)
                .putString("PREF_DOWNLOAD_DIR", newDownloadDir)
                .putInt("PREF_THREADS", newThreads)
                .putString("PREF_PASSIVE", newPassive)
                .apply()

            binding.etSharedDir.setText(newDir)
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

        DialogUtils.applyGlassBlur(dialog)

        btnUp.setOnClickListener {
            val parent = currentDir.parentFile
            if (parent != null && parent.canRead() && currentDir.absolutePath != "/storage") {
                currentDir = parent
                updateFolderList()
            } else {
                showStorageVolumeChooserDialog { selectedVol ->
                    currentDir = selectedVol
                    updateFolderList()
                }
            }
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSelect.setOnClickListener {
            onFolderSelected(currentDir.absolutePath)
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun showAboutDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_about, null)
        val btnClose = dialogView.findViewById<Button>(R.id.btnCloseAbout)

        val dialog = AlertDialog.Builder(this, com.google.android.material.R.style.ThemeOverlay_Material3_MaterialAlertDialog)
            .setView(dialogView)
            .create()

        DialogUtils.applyGlassBlur(dialog)

        btnClose.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun startSyncService() {
        try {
            val intent = Intent(this, RealtimeSyncService::class.java)
            ContextCompat.startForegroundService(this, intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun checkPermissions() {
        startSyncService()
        requestBatteryOptimizationExemption()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 102)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                })
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), 101)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(ftpReceiver)
    }
}
