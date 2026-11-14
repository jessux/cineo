package com.cineo.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Base64
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * Relais HTTP local : le Chromecast lit le flux via le téléphone, qui se reconnecte au serveur IPTV
 * quand celui-ci coupe (erreur 103 côté Chromecast, dont le lecteur ne sait pas se reconnecter).
 */
object CastRelay {
    private var server: ServerSocket? = null
    private val finals = java.util.concurrent.ConcurrentHashMap<String, String>() // playlist -> URL après redirection

    /** Démarre le relais si besoin et renvoie l'URL à donner au Chromecast, ou null si pas de Wi-Fi. */
    fun url(c: Context, upstream: String): String? {
        val ip = lanIp() ?: return null
        val s = synchronized(this) {
            server?.takeIf { !it.isClosed } ?: ServerSocket(0).also { srv ->
                server = srv
                thread(isDaemon = true, name = "cast-relay") {
                    while (!srv.isClosed) runCatching { srv.accept() }.getOrNull()?.let { so -> thread(isDaemon = true) { serve(so) } }
                }
            }
        }
        runCatching {
            val i = Intent(c, CastRelayService::class.java)
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i)
        }
        val name = upstream.substringBefore('?').substringAfterLast('/')
        return "http://$ip:${s.localPort}/${enc(upstream)}/$name"
    }

    fun stop() { synchronized(this) { runCatching { server?.close() }; server = null } }

    private fun enc(u: String) = Base64.encodeToString(u.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    private fun dec(s: String) = String(Base64.decode(s, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))

    private fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
    }.getOrNull()

    private fun serve(so: Socket) { so.use {
        val inp = so.getInputStream()
        val req = readHead(inp) ?: return
        val (method, path) = req.first.split(" ").let { it.getOrElse(0) { "" } to it.getOrElse(1) { "" } }
        val out = so.getOutputStream()
        if (method == "OPTIONS") { head(out, "204 No Content", mapOf()); return }
        val upstream = runCatching { dec(path.trimStart('/').substringBefore('/')) }.getOrNull()
            ?.takeIf { it.startsWith("http") } ?: run { head(out, "404 Not Found", mapOf("Content-Length" to "0")); return }
        val range = req.second["range"]?.removePrefix("bytes=")?.substringBefore('-')?.toLongOrNull()
        val playlist = upstream.substringBefore('?').endsWith(".m3u8")
        if (playlist) playlist(out, upstream) else stream(out, upstream, range, method == "HEAD", resume = !upstream.substringBefore('?').endsWith(".ts"))
    } }

    /** Réécrit la playlist HLS pour que les segments passent aussi par le relais. */
    private fun playlist(out: OutputStream, upstream: String) {
        // On réutilise l'URL après redirection pour ne pas ouvrir une nouvelle session à chaque rafraîchissement
        val conn = finals[upstream]?.let { open(it, null, tries = 1) } ?: open(upstream, null) ?: run { head(out, "502 Bad Gateway", mapOf("Content-Length" to "0")); return }
        val base = conn.url
        finals[upstream] = base.toString()
        val body = try { conn.inputStream.bufferedReader().readText() } finally { conn.disconnect() }
        val text = body.lines().joinToString("\n") { l ->
            if (l.isBlank() || l.startsWith("#")) l else {
                val abs = URL(base, l.trim()).toString()
                "/${enc(abs)}/${abs.substringBefore('?').substringAfterLast('/')}"
            }
        }.toByteArray()
        head(out, "200 OK", mapOf("Content-Type" to "application/x-mpegURL", "Content-Length" to "${text.size}", "Cache-Control" to "no-cache"))
        out.write(text); out.flush()
    }

    /** Relaie le flux ; en cas de coupure du serveur, reprend à l'octet où l'on s'était arrêté. */
    private fun stream(out: OutputStream, upstream: String, start: Long?, headOnly: Boolean, resume: Boolean) {
        var conn = open(upstream, start) ?: run { head(out, "502 Bad Gateway", mapOf("Content-Length" to "0")); return }
        val code = conn.responseCode
        val len = conn.getHeaderField("Content-Length")?.toLongOrNull()
        val total = conn.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
            ?: if (code == 200) len else null
        val from = if (code == 206) start ?: 0L else 0L
        val h = mutableMapOf("Content-Type" to (conn.contentType ?: "video/mp4"), "Accept-Ranges" to "bytes")
        len?.let { h["Content-Length"] = "$it" }
        if (code == 206 && total != null && len != null) h["Content-Range"] = "bytes $from-${from + len - 1}/$total"
        head(out, if (code == 206) "206 Partial Content" else "200 OK", h)
        if (headOnly) { conn.disconnect(); return }
        val end = if (len != null) from + len else null
        var pos = from
        val buf = ByteArray(64 * 1024)
        var fails = 0
        while (true) {
            val n = try { conn.inputStream.read(buf) } catch (e: Exception) { -2 }
            if (n > 0) {
                try { out.write(buf, 0, n) } catch (e: Exception) { conn.disconnect(); return } // le Chromecast a fermé
                pos += n; fails = 0; continue
            }
            conn.disconnect()
            if (n == -1 && (end == null || pos >= end)) break
            if (!resume) break // segment de direct : le Chromecast le redemande lui-même
            // Coupure du serveur : on se reconnecte et on reprend à `pos`
            if (++fails > 10) break
            Thread.sleep(1000L * fails)
            conn = open(upstream, pos) ?: break
            if (pos > 0 && conn.responseCode != 206) { conn.disconnect(); break }
        }
        runCatching { out.flush() }
    }

    /** Ouvre le flux ; réessaie tant que le serveur refuse (connexion précédente pas encore libérée). */
    private fun open(u: String, from: Long?, tries: Int = 8): HttpURLConnection? {
        repeat(tries) { attempt ->
            val conn = runCatching {
                (URL(u).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10_000; readTimeout = 20_000
                    if (from != null && from > 0) setRequestProperty("Range", "bytes=$from-")
                }.also { it.responseCode }
            }.getOrNull()
            if (conn != null && conn.responseCode in 200..299) return conn
            conn?.disconnect()
            if (attempt < tries - 1) Thread.sleep(1500L * (attempt + 1))
        }
        return null
    }

    private fun readHead(inp: InputStream): Pair<String, Map<String, String>>? {
        val lines = mutableListOf<String>()
        val sb = StringBuilder()
        while (true) {
            val b = inp.read(); if (b < 0) return null
            if (b == '\n'.code) {
                val l = sb.toString().trimEnd('\r'); sb.clear()
                if (l.isEmpty()) break
                lines += l
            } else sb.append(b.toChar())
        }
        val h = lines.drop(1).associate { it.substringBefore(':').trim().lowercase() to it.substringAfter(':').trim() }
        return lines.firstOrNull()?.let { it to h }
    }

    private fun head(out: OutputStream, status: String, h: Map<String, String>) {
        val all = h + mapOf("Access-Control-Allow-Origin" to "*", "Access-Control-Allow-Headers" to "*",
            "Access-Control-Expose-Headers" to "Content-Length, Content-Range", "Connection" to "close")
        out.write(("HTTP/1.1 $status\r\n" + all.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n").toByteArray())
        out.flush()
    }
}

/** Garde le relais actif écran éteint (sinon Android coupe le Wi-Fi / le processus). */
class CastRelayService : Service() {
    private var wifi: WifiManager.WifiLock? = null
    private var wake: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @Suppress("DEPRECATION")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("cast", "Chromecast", NotificationManager.IMPORTANCE_LOW))
        val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "cast") else Notification.Builder(this))
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Diffusion sur Chromecast").setOngoing(true).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK) else startForeground(1, n)
        if (wifi == null) {
            wifi = (applicationContext.getSystemService(WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cineo:cast").apply { acquire() }
            wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cineo:cast").apply { acquire() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { wifi?.release() }; runCatching { wake?.release() }
        CastRelay.stop()
        super.onDestroy()
    }

    companion object {
        fun stop(c: Context) { runCatching { c.stopService(Intent(c, CastRelayService::class.java)) } }
    }
}
