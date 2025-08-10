package com.marinov.colegioetapa

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.stream.Collectors

object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val PREFS_NAME = "UpdatePrefs"
    private const val KEY_LAST_VERSION = "last_version"

    interface UpdateListener {
        fun onUpdateAvailable(url: String)
        fun onUpToDate()
        fun onError(message: String)
    }

    fun checkForUpdate(context: Context, listener: UpdateListener) {
        Thread {
            runCatching {
                val url = URL("https://api.github.com/repos/etapaapp/EtapaAppForSmartwatch/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/vnd.github.v3+json")
                    setRequestProperty("User-Agent", "EtapaApp-Android")
                    connectTimeout = 10000
                }

                when (conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> {
                        val json = readResponseStream(conn)
                        val release = JSONObject(json)
                        val latestVersion = release.getString("tag_name")
                        val currentVersion = BuildConfig.VERSION_NAME

                        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        val lastNotifiedVersion = prefs.getString(KEY_LAST_VERSION, "") ?: ""

                        if (isVersionGreater(latestVersion, currentVersion)) {
                            if (latestVersion != lastNotifiedVersion) {
                                prefs.edit { putString(KEY_LAST_VERSION, latestVersion) }
                                listener.onUpdateAvailable(release.getString("html_url"))
                            } else {
                                listener.onUpToDate()
                            }
                        } else {
                            listener.onUpToDate()
                        }
                    }
                    else -> listener.onError("HTTP error: ${conn.responseCode}")
                }
            }.onFailure { e ->
                Log.e(TAG, "Erro na verificação", e)
                listener.onError(e.message ?: "Erro desconhecido")
            }
        }.start()
    }

    private fun readResponseStream(conn: HttpURLConnection): String {
        return conn.inputStream.use { inputStream ->
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.lines().collect(Collectors.joining("\n"))
            }
        }
    }

    private fun normalizeVersion(version: String): String {
        return version.replace(Regex("^[^0-9]+"), "")
    }

    fun isVersionGreater(newVersion: String, currentVersion: String): Boolean {
        val normalizedNew = normalizeVersion(newVersion)
        val normalizedCurrent = normalizeVersion(currentVersion)

        val newParts = normalizedNew.split(".").map { it.toIntOrNull() ?: 0 }
        val currentParts = normalizedCurrent.split(".").map { it.toIntOrNull() ?: 0 }

        for (i in 0 until maxOf(newParts.size, currentParts.size)) {
            val newPart = newParts.getOrElse(i) { 0 }
            val currentPart = currentParts.getOrElse(i) { 0 }

            when {
                newPart > currentPart -> return true
                newPart < currentPart -> return false
            }
        }
        return false
    }
}