package com.ayush.ftpclient

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class BackupProfile(
    val serverId: String,
    val serverAlias: String,
    val localFolderPath: String,
    val remoteFolderPath: String = "/",
    val autoBackupEnabled: Boolean,
    val frequency: String,
    val customMinutes: Int = 15
)

object BackupManager {
    private const val PREFS_NAME = "FTP_BACKUP_PROFILES_PREFS"
    private const val KEY_PROFILES_JSON = "BACKUP_PROFILES_JSON"

    fun getProfileForServer(context: Context, serverId: String, serverAlias: String): BackupProfile {
        val list = getAllProfiles(context)
        return list.firstOrNull { it.serverId == serverId } ?: BackupProfile(
            serverId = serverId,
            serverAlias = serverAlias,
            localFolderPath = "/storage/emulated/0/Download/FTPApp",
            remoteFolderPath = "/",
            autoBackupEnabled = false,
            frequency = "Realtime Sync",
            customMinutes = 15
        )
    }

    fun getAllProfiles(context: Context): List<BackupProfile> {
        val list = mutableListOf<BackupProfile>()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_PROFILES_JSON, "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    BackupProfile(
                        serverId = obj.optString("serverId", ""),
                        serverAlias = obj.optString("serverAlias", "FTP Server"),
                        localFolderPath = obj.optString("localFolderPath", "/storage/emulated/0/Download/FTPApp"),
                        remoteFolderPath = obj.optString("remoteFolderPath", "/"),
                        autoBackupEnabled = obj.optBoolean("autoBackupEnabled", false),
                        frequency = obj.optString("frequency", "Realtime Sync"),
                        customMinutes = obj.optInt("customMinutes", 15)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveProfile(context: Context, profile: BackupProfile) {
        val currentList = getAllProfiles(context).toMutableList()
        currentList.removeAll { it.serverId == profile.serverId }
        currentList.add(profile)

        val jsonArray = JSONArray()
        for (item in currentList) {
            val obj = JSONObject().apply {
                put("serverId", item.serverId)
                put("serverAlias", item.serverAlias)
                put("localFolderPath", item.localFolderPath)
                put("remoteFolderPath", item.remoteFolderPath)
                put("autoBackupEnabled", item.autoBackupEnabled)
                put("frequency", item.frequency)
                put("customMinutes", item.customMinutes)
            }
            jsonArray.put(obj)
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PROFILES_JSON, jsonArray.toString()).apply()
    }
}
