package com.ayush.ftpclient

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.io.CopyStreamEvent
import org.apache.commons.net.io.CopyStreamListener
import java.io.File
import java.util.Locale

object RealtimeSyncEngine {

    @Volatile
    var isSyncing = false
        private set

    @Volatile
    var activeServerAlias = ""
        private set

    @Volatile
    var syncedBytesTotal = 0L
        private set

    @Volatile
    var remainingBytesTotal = 0L
        private set

    @Volatile
    var currentSpeedText = "0.0 MB/s"
        private set

    private val syncedFilesCache = mutableSetOf<String>()

    suspend fun triggerSyncCheck(context: Context, savedServers: List<SavedFtpServer>) {
        if (isSyncing) return

        val activeProfiles = BackupManager.getAllProfiles(context).filter { it.autoBackupEnabled }
        if (activeProfiles.isEmpty()) return

        val onlineServers = savedServers.filter { it.isOnline }
        if (onlineServers.isEmpty()) return

        isSyncing = true
        withContext(Dispatchers.IO) {
            try {
                for (profile in activeProfiles) {
                    val server = onlineServers.firstOrNull { it.id == profile.serverId } ?: continue
                    activeServerAlias = server.alias

                    val localDir = File(profile.localFolderPath)
                    if (!localDir.exists() || !localDir.isDirectory) continue

                    val localFiles = localDir.listFiles { f -> f.isFile && !f.isHidden } ?: continue
                    if (localFiles.isEmpty()) continue

                    syncLocalFolderToServer(context, server, profile, localFiles)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSyncing = false
            }
        }
    }

    private fun cleanHost(raw: String): String {
        return raw.removePrefix("ftp://").removePrefix("http://").substringBefore(":").substringBefore("/").trim()
    }

    private fun syncLocalFolderToServer(
        context: Context,
        server: SavedFtpServer,
        profile: BackupProfile,
        localFiles: Array<File>
    ) {
        var client: FTPClient? = null
        try {
            client = FTPClient().apply {
                connectTimeout = 3000
                defaultTimeout = 3000
            }

            var lastTimeMs = System.currentTimeMillis()
            var lastBytes = 0L

            client.copyStreamListener = object : CopyStreamListener {
                override fun bytesTransferred(event: CopyStreamEvent?) {}

                override fun bytesTransferred(totalBytesTransferred: Long, bytesTransferred: Int, streamSize: Long) {
                    val now = System.currentTimeMillis()
                    val deltaMs = now - lastTimeMs

                    if (deltaMs >= 200) {
                        val deltaBytes = totalBytesTransferred - lastBytes
                        val bytesPerSec = (deltaBytes * 1000.0) / deltaMs
                        val mbPerSec = bytesPerSec / (1024.0 * 1024.0)

                        currentSpeedText = if (mbPerSec >= 1.0) {
                            String.format(Locale.US, "%.1f MB/s", mbPerSec)
                        } else {
                            val kbPerSec = bytesPerSec / 1024.0
                            String.format(Locale.US, "%.0f KB/s", kbPerSec)
                        }

                        lastTimeMs = now
                        lastBytes = totalBytesTransferred
                    }
                }
            }

            val host = cleanHost(server.host)
            client.connect(host, server.port)
            if (!client.isConnected) return

            val user = if (server.isAnonymous) "anonymous" else server.username
            val pass = if (server.isAnonymous) "" else server.password

            if (!client.login(user, pass)) {
                client.disconnect()
                return
            }

            client.enterLocalPassiveMode()
            client.setFileType(FTP.BINARY_FILE_TYPE)

            val remotePath = profile.remoteFolderPath.ifEmpty { "/" }
            client.changeWorkingDirectory(remotePath)

            val remoteFiles = client.listFiles() ?: emptyArray()
            val remoteFileNames = remoteFiles.map { it.name }.toSet()

            val filesToUpload = localFiles.filter { localFile ->
                val cacheKey = "${server.id}_${localFile.name}_${localFile.length()}"
                !remoteFileNames.contains(localFile.name) && !syncedFilesCache.contains(cacheKey)
            }

            remainingBytesTotal = filesToUpload.sumOf { it.length() }

            for (localFile in filesToUpload) {
                val cacheKey = "${server.id}_${localFile.name}_${localFile.length()}"

                lastTimeMs = System.currentTimeMillis()
                lastBytes = 0L

                val inputStream = localFile.inputStream()
                val remoteTarget = if (remotePath.endsWith("/")) "$remotePath${localFile.name}" else "$remotePath/${localFile.name}"

                val success = client.storeFile(remoteTarget, inputStream)
                inputStream.close()

                if (success) {
                    syncedFilesCache.add(cacheKey)
                    syncedBytesTotal += localFile.length()
                    remainingBytesTotal = (remainingBytesTotal - localFile.length()).coerceAtLeast(0L)

                    val formattedSize = HistoryManager.formatFileSize(localFile.length())
                    HistoryManager.addRecord(
                        context = context,
                        type = HistoryManager.TYPE_SENT,
                        fileName = localFile.name,
                        details = "Auto-Synced to ${server.alias}",
                        fileSize = formattedSize,
                        localFilePath = localFile.absolutePath
                    )

                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "⚡ Auto-Synced ${localFile.name} to ${server.alias}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { client?.disconnect() } catch (_: Exception) {}
        }
    }
}
