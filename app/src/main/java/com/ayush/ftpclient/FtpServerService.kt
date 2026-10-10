package com.ayush.ftpclient

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.apache.ftpserver.ConnectionConfigFactory
import org.apache.ftpserver.DataConnectionConfigurationFactory
import org.apache.ftpserver.FtpServer
import org.apache.ftpserver.FtpServerFactory
import org.apache.ftpserver.ftplet.DefaultFtplet
import org.apache.ftpserver.ftplet.FtpRequest
import org.apache.ftpserver.ftplet.FtpSession
import org.apache.ftpserver.ftplet.Ftplet
import org.apache.ftpserver.ftplet.FtpletResult
import org.apache.ftpserver.ipfilter.SessionFilter
import org.apache.ftpserver.listener.ListenerFactory
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory
import org.apache.ftpserver.usermanager.impl.BaseUser
import org.apache.ftpserver.usermanager.impl.WritePermission
import org.apache.mina.filter.codec.ProtocolCodecFilter
import java.io.File

class FtpServerService : Service() {

    companion object {
        const val ACTION_STOP_SERVER = "com.ayush.ftpclient.ACTION_STOP_SERVER"
        const val NOTIFICATION_ID = 1
    }

    private var ftpServer: FtpServer? = null
    private val PORT = 2221
    private val CHANNEL_ID = "FtpServerChannel"

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    private fun acquireLocks() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "FTPApp::ServerWakeLock").apply {
                acquire(10 * 60 * 1000L)
            }

            val wm = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "FTPApp::ServerWifiLock").apply {
                acquire()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun releaseLocks() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    @Suppress("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVER) {
            stopServer()
            return START_NOT_STICKY
        }

        acquireLocks()

        val username = intent?.getStringExtra("USER") ?: "anonymous"
        val password = intent?.getStringExtra("PASS") ?: ""
        val isAnonymous = intent?.getBooleanExtra("ANON", false) ?: false
        val sharedDir = intent?.getStringExtra("DIR") ?: "/storage/emulated/0"

        val prefs = getSharedPreferences("FTP_SETTINGS", MODE_PRIVATE)
        val serverPort = prefs.getInt("PREF_PORT", PORT)
        val maxThreads = prefs.getInt("PREF_THREADS", 10)
        val passivePorts = prefs.getString("PREF_PASSIVE", "50000-50100") ?: "50000-50100"

        startForeground(NOTIFICATION_ID, buildNotification("Starting FTP Server..."))

        Thread {
            try {
                val serverFactory = FtpServerFactory()

                val connectionConfigFactory = ConnectionConfigFactory()
                connectionConfigFactory.maxThreads = maxThreads
                serverFactory.connectionConfig = connectionConfigFactory.createConnectionConfig()

                val factory = ListenerFactory()
                factory.port = serverPort

                factory.sessionFilter = SessionFilter { session ->
                    if (session.filterChain.contains("codec")) {
                        session.filterChain.replace(
                            "codec",
                            ProtocolCodecFilter(AndroidFtpServerProtocolCodecFactory())
                        )
                    }
                    true
                }

                val ipAddress = NetworkUtils.getLocalIpAddress()
                val dataConnFactory = DataConnectionConfigurationFactory()
                dataConnFactory.passivePorts = passivePorts
                dataConnFactory.passiveExternalAddress = ipAddress

                factory.dataConnectionConfiguration = dataConnFactory.createDataConnectionConfiguration()
                serverFactory.addListener("default", factory.createListener())

                val ftplets = HashMap<String, Ftplet>()
                ftplets["historyFtplet"] = object : DefaultFtplet() {
                    override fun onConnect(session: FtpSession): FtpletResult? {
                        val clientIp = session.clientAddress?.address?.hostAddress ?: "Client"
                        HistoryManager.addRecord(
                            context = applicationContext,
                            type = HistoryManager.TYPE_CONNECTION,
                            fileName = "Client Connected to Server",
                            details = "Client IP: $clientIp"
                        )
                        return null
                    }

                    override fun onDisconnect(session: FtpSession): FtpletResult? {
                        val clientIp = session.clientAddress?.address?.hostAddress ?: "Client"
                        HistoryManager.addRecord(
                            context = applicationContext,
                            type = HistoryManager.TYPE_CONNECTION,
                            fileName = "Client Disconnected",
                            details = "Client IP: $clientIp"
                        )
                        return null
                    }

                    override fun onUploadEnd(
                        session: FtpSession,
                        request: FtpRequest
                    ): FtpletResult? {
                        val fileName = request.argument ?: "File"
                        val clientIp = session.clientAddress?.address?.hostAddress ?: "Client"
                        val localFile = File(sharedDir, fileName)
                        val fileSizeStr = if (localFile.exists()) HistoryManager.formatFileSize(localFile.length()) else ""
                        HistoryManager.addRecord(
                            context = applicationContext,
                            type = HistoryManager.TYPE_RECEIVED,
                            fileName = fileName,
                            details = "Received from $clientIp",
                            fileSize = fileSizeStr,
                            localFilePath = if (localFile.exists()) localFile.absolutePath else ""
                        )
                        return null
                    }

                    override fun onDownloadEnd(
                        session: FtpSession,
                        request: FtpRequest
                    ): FtpletResult? {
                        val fileName = request.argument ?: "File"
                        val clientIp = session.clientAddress?.address?.hostAddress ?: "Client"
                        val localFile = File(sharedDir, fileName)
                        val fileSizeStr = if (localFile.exists()) HistoryManager.formatFileSize(localFile.length()) else ""
                        HistoryManager.addRecord(
                            context = applicationContext,
                            type = HistoryManager.TYPE_SENT,
                            fileName = fileName,
                            details = "Sent to $clientIp",
                            fileSize = fileSizeStr,
                            localFilePath = if (localFile.exists()) localFile.absolutePath else ""
                        )
                        return null
                    }
                }
                serverFactory.ftplets = ftplets

                val userManagerFactory = PropertiesUserManagerFactory()
                val propFile = File(cacheDir, "users.properties")
                if (propFile.exists()) propFile.delete()
                propFile.createNewFile()
                userManagerFactory.file = propFile
                val userManager = userManagerFactory.createUserManager()

                val user = BaseUser()
                user.name = if (isAnonymous) "anonymous" else username
                user.password = if (isAnonymous) "" else password
                user.homeDirectory = sharedDir
                user.authorities = listOf(WritePermission())

                userManager.save(user)
                serverFactory.userManager = userManager

                ftpServer = serverFactory.createServer()
                ftpServer?.start()

                val statusText = "ONLINE @ ftp://$ipAddress:$serverPort"

                if ((Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) ||
                    (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                ) {
                    val notificationManager = getSystemService(NotificationManager::class.java)
                    notificationManager?.notify(NOTIFICATION_ID, buildNotification(statusText))
                }

                val updateIntent = Intent("FTP_UPDATE").apply {
                    setPackage(packageName)
                    putExtra("STATUS", "STATUS: $statusText")
                }
                sendBroadcast(updateIntent)

            } catch (e: Exception) {
                e.printStackTrace()
                val errorIntent = Intent("FTP_UPDATE").apply {
                    setPackage(packageName)
                    putExtra("STATUS", "STATUS: ERROR (${e.message})")
                }
                sendBroadcast(errorIntent)
            }
        }.start()

        return START_STICKY
    }

    private fun stopServer() {
        releaseLocks()
        try {
            ftpServer?.stop()
            ftpServer = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
        val stopIntent = Intent("FTP_UPDATE").apply {
            setPackage(packageName)
            putExtra("STATUS", "STATUS: OFFLINE")
        }
        sendBroadcast(stopIntent)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "FTP Server Active", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, FtpServerService::class.java).apply {
            action = ACTION_STOP_SERVER
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("FTPApp Server Active")
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "STOP SERVER",
                stopPendingIntent
            )
            .build()
    }
}
