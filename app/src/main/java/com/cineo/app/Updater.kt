package com.cineo.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Met à jour l'app depuis la dernière release GitHub (tag vX.Y.N, asset .apk). */
object Updater {
    private const val REPO = "jessux/cineo"
    private const val UA = "Cineo-Updater"

    private fun installedCode(c: Context): Long =
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    /** Retourne (versionCode de la release, URL de l'APK) ou null. */
    private fun latest(): Pair<Long, String>? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", UA)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            if (conn.responseCode !in 200..299) return null
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val code = json.optString("tag_name").substringAfterLast('.').toLongOrNull() ?: return null
            val assets = json.optJSONArray("assets") ?: return null
            val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                .firstOrNull { it.optString("name").endsWith(".apk") } ?: return null
            return code to apk.getString("browser_download_url")
        } finally { conn.disconnect() }
    }

    private fun download(url: String, dest: File) {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000; conn.readTimeout = 60000
        conn.setRequestProperty("User-Agent", UA)
        try {
            if (conn.responseCode !in 200..299) throw Exception("HTTP ${conn.responseCode}")
            conn.inputStream.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        } finally { conn.disconnect() }
    }

    private fun canInstall(c: Context) = Build.VERSION.SDK_INT < Build.VERSION_CODES.O || c.packageManager.canRequestPackageInstalls()

    /** Si une release plus récente existe : télécharge l'APK et ouvre l'écran d'installation système. Erreurs réseau ignorées. */
    suspend fun checkAndInstall(c: Context) = withContext(Dispatchers.IO) {
        runCatching {
            val (code, url) = latest() ?: return@runCatching
            if (code <= installedCode(c)) return@runCatching
            if (!canInstall(c)) {
                c.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return@runCatching
            }
            val tmp = File(c.cacheDir, "update.apk.part")
            val apk = File(c.cacheDir, "update.apk")
            try { download(url, tmp) } catch (e: Exception) { tmp.delete(); return@runCatching }
            apk.delete(); tmp.renameTo(apk)
            val uri = FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", apk)
            c.startActivity(Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
