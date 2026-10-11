package com.ayush.ftpclient

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
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

                    val localFiles = localDir.listFiles { f -> !f.isHidden } ?: continue
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
                connectTimeout = 4000
                defaultTimeout = 4000
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

            val baseRemotePath = profile.remoteFolderPath.ifEmpty { "/" }

            syncDirectoryRecursively(context, client, server, File(profile.localFolderPath), baseRemotePath)

        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            try { client?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun syncDirectoryRecursively(
        context: Context,
        client: FTPClient,
        server: SavedFtpServer,
        localDir: File,
        remotePath: String
    ) {
        if (!localDir.exists() || !localDir.isDirectory) return

        try {
            client.changeWorkingDirectory(remotePath)
        } catch (_: Exception) {
            client.makeDirectory(remotePath)
            client.changeWorkingDirectory(remotePath)
        }

        val remoteFiles = client.listFiles() ?: emptyArray()
        val remoteFileNames = remoteFiles.map { it.name }.toSet()

        val entries = localDir.listFiles { f -> !f.isHidden } ?: return

        for (entry in entries) {
            if (entry.isDirectory) {
                val subRemotePath = if (remotePath.endsWith("/")) "$remotePath${entry.name}" else "$remotePath/${entry.name}"
                syncDirectoryRecursively(context, client, server, entry, subRemotePath)
            } else if (entry.isFile) {
                val cacheKey = "${server.id}_${entry.absolutePath}_${entry.length()}_${entry.lastModified()}"

                if (remoteFileNames.contains(entry.name) || syncedFilesCache.contains(cacheKey)) {
                    continue
                }

                val inputStream = entry.inputStream()
                val remoteTarget = if (remotePath.endsWith("/")) "$remotePath${entry.name}" else "$remotePath/${entry.name}"

                val success = client.storeFile(remoteTarget, inputStream)
                inputStream.close()

                if (success) {
                    syncedFilesCache.add(cacheKey)
                    syncedBytesTotal += entry.length()

                    val formattedSize = HistoryManager.formatFileSize(entry.length())
                    HistoryManager.addRecord(
                        context = context,
                        type = HistoryManager.TYPE_SENT,
                        fileName = entry.name,
                        details = "Auto-Synced to ${server.alias}",
                        fileSize = formattedSize,
                        localFilePath = entry.absolutePath
                    )

                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, "⚡ Auto-Synced ${entry.name} to ${server.alias}", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}
