package com.ayush.ftpclient

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SavedFtpServer(
    val id: String,
    val alias: String,
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val isAnonymous: Boolean,
    @Volatile var isOnline: Boolean = false
)

object SavedServerManager {
    private const val PREFS_NAME = "FTP_SAVED_SERVERS_PREFS"
    private const val KEY_SERVERS_JSON = "SAVED_SERVERS_JSON"

    fun getSavedServers(context: Context): List<SavedFtpServer> {
        val list = mutableListOf<SavedFtpServer>()
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val jsonStr = prefs.getString(KEY_SERVERS_JSON, "[]") ?: "[]"
        try {
            val jsonArray = JSONArray(jsonStr)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    SavedFtpServer(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        alias = obj.optString("alias", obj.optString("host")),
                        host = obj.optString("host", ""),
                        port = obj.optInt("port", 2221),
                        username = obj.optString("username", "anonymous"),
                        password = obj.optString("password", ""),
                        isAnonymous = obj.optBoolean("isAnonymous", false)
                    )
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return list
    }

    fun saveServer(context: Context, server: SavedFtpServer) {
        val currentList = getSavedServers(context).toMutableList()
        currentList.removeAll { it.id == server.id || (it.host == server.host && it.port == server.port) }
        currentList.add(0, server)
        saveList(context, currentList)
    }

    fun deleteServer(context: Context, serverId: String) {
        val currentList = getSavedServers(context).toMutableList()
        currentList.removeAll { it.id == serverId }
        saveList(context, currentList)
    }

    private fun saveList(context: Context, list: List<SavedFtpServer>) {
        val jsonArray = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("alias", item.alias)
                put("host", item.host)
                put("port", item.port)
                put("username", item.username)
                put("password", item.password)
                put("isAnonymous", item.isAnonymous)
            }
            jsonArray.put(obj)
        }
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_SERVERS_JSON, jsonArray.toString()).apply()
    }
}
