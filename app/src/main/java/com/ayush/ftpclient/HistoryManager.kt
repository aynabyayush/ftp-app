package com.ayush.ftpclient

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class HistoryItem(
    val type: String, // "SENT", "RECEIVED", or "CONNECTION"
    val fileName: String,
    val fileSize: String,
    val details: String,
    val timestamp: String,
    val localFilePath: String = ""
)

object HistoryManager {
    const val TYPE_SENT = "SENT"
    const val TYPE_RECEIVED = "RECEIVED"
    const val TYPE_CONNECTION = "CONNECTION"

    private const val PREFS_NAME = "FTP_HISTORY_PREFS"
    private const val KEY_HISTORY_JSON = "HISTORY_JSON"

    fun addRecord(
        context: Context,
        type: String,
        fileName: String,
        details: String,
        fileSize: String = "",
        localFilePath: String = ""
    ) {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_HISTORY_JSON, "[]") ?: "[]"
            val jsonArray = JSONArray(jsonString)

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

            val newItem = JSONObject().apply {
                put("type", type)
                put("fileName", fileName)
                put("fileSize", fileSize)
                put("details", details)
                put("timestamp", timestamp)
                put("localFilePath", localFilePath)
            }

            val newArray = JSONArray()
            newArray.put(newItem)
            for (i in 0 until minOf(jsonArray.length(), 99)) {
                newArray.put(jsonArray.get(i))
            }

            prefs.edit().putString(KEY_HISTORY_JSON, newArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getRecords(context: Context): List<HistoryItem> {
        val list = mutableListOf<HistoryItem>()
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_HISTORY_JSON, "[]") ?: "[]"
            val jsonArray = JSONArray(jsonString)

            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    HistoryItem(
                        type = obj.optString("type", TYPE_SENT),
                        fileName = obj.optString("fileName", "Unknown File"),
                        fileSize = obj.optString("fileSize", ""),
                        details = obj.optString("details", ""),
                        timestamp = obj.optString("timestamp", ""),
                        localFilePath = obj.optString("localFilePath", "")
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun clearRecords(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_HISTORY_JSON).apply()
    }

    fun openFile(context: Context, item: HistoryItem) {
        if (item.localFilePath.isEmpty()) {
            Toast.makeText(context, "No local file path recorded.", Toast.LENGTH_SHORT).show()
            return
        }

        val file = File(item.localFilePath)
        if (!file.exists()) {
            Toast.makeText(context, "File does not exist: ${file.name}", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                file
            )
            val extension = file.extension.lowercase(Locale.getDefault())
            var mimeType = context.contentResolver.getType(uri)
            if (mimeType == null || mimeType == "*/*") {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "*/*"
            }

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(Intent.createChooser(intent, "Open File"))
        } catch (e: Throwable) {
            Toast.makeText(context, "Cannot open file: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return ""
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt()
        return String.format(Locale.US, "%.1f %s", bytes / Math.pow(1024.0, digitGroups.toDouble()), units[digitGroups])
    }
}
