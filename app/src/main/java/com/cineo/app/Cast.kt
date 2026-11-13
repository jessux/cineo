package com.cineo.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL
import android.view.ContextThemeWrapper
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaError
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.cast.framework.media.widget.ExpandedControllerActivity
import com.google.android.gms.common.images.WebImage

/** ExpandedControllerActivity est abstraite : sous-classe obligatoire. */
class CastControllerActivity : ExpandedControllerActivity()

class CastOptionsProvider : OptionsProvider {
    override fun getCastOptions(ctx: Context): CastOptions = CastOptions.Builder()
        .setReceiverApplicationId("CC1AD845") // Default Media Receiver
        .setCastMediaOptions(
            CastMediaOptions.Builder().setExpandedControllerActivityClassName(CastControllerActivity::class.java.name).build()
        ).build()
    override fun getAdditionalSessionProviders(ctx: Context): List<SessionProvider>? = null
}

object Cast {
    @Suppress("DEPRECATION")
    fun context(c: Context): CastContext? = runCatching { CastContext.getSharedInstance(c.applicationContext) }.getOrNull()

    /** Envoie le flux au Chromecast si une session est active. Retourne false sinon. */
    fun play(c: Context, url: String, title: String, image: String?, live: Boolean): Boolean {
        val client = context(c)?.sessionManager?.currentCastSession?.takeIf { it.isConnected }?.remoteMediaClient ?: return false
        app = c.applicationContext
        val u = if (live) url.removeSuffix(".ts") + ".m3u8" else url
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            image?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val type = when (u.substringAfterLast('.').lowercase()) {
            "m3u8" -> "application/x-mpegURL"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
            "ts" -> "video/mp2t"; "avi" -> "video/x-msvideo"; else -> "video/mp4"
        }
        // Les serveurs Xtream redirigent vers une URL à jeton : sans ça, chaque requête du Chromecast
        // (buffer, seek, rechargement de la playlist en direct) repasse par l'URL d'origine, ouvre une nouvelle connexion et dépasse la limite → coupure.
        Thread {
            val final = resolve(u)
            val info = MediaInfo.Builder(final).setContentType(type).setMetadata(meta)
                .setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED).build()
            Handler(Looper.getMainLooper()).post {
                client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
                    .setResultCallback { r -> if (!r.status.isSuccess) toast(c, "Chromecast : échec du chargement (${r.status.statusCode})") }
                if (watched !== client) { watched = client; client.registerCallback(errCb) }
            }
        }.start()
        c.startActivity(Intent(c, CastControllerActivity::class.java))
        return true
    }

    private lateinit var app: Context
    private var watched: RemoteMediaClient? = null
    private fun toast(c: Context, m: String) = Toast.makeText(c.applicationContext, m, Toast.LENGTH_LONG).show()
    /** Diagnostic : affiche la raison de l'arrêt côté Chromecast. */
    private val errCb = object : RemoteMediaClient.Callback() {
        override fun onMediaError(e: MediaError) { toast(app, "Chromecast : erreur ${e.detailedErrorCode ?: ""} ${e.reason ?: ""}") }
    }

    /** Suit les redirections sans télécharger le flux ; renvoie l'URL d'origine en cas d'échec. */
    private fun resolve(url: String): String = runCatching {
        var cur = url
        repeat(5) {
            val conn = (URL(cur).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false; connectTimeout = 5000; readTimeout = 5000
                setRequestProperty("Range", "bytes=0-0")
            }
            try {
                if (conn.responseCode !in 300..399) return@runCatching cur
                cur = URL(URL(cur), conn.getHeaderField("Location") ?: return@runCatching cur).toString()
            } finally { conn.disconnect() }
        }
        cur
    }.getOrDefault(url)
}

@Composable
fun CastButton(modifier: Modifier = Modifier) = AndroidView(
    factory = { c ->
        MediaRouteButton(ContextThemeWrapper(c, androidx.appcompat.R.style.Theme_AppCompat)).also {
            runCatching { CastButtonFactory.setUpMediaRouteButton(c.applicationContext, it) }
        }
    },
    modifier = modifier.size(48.dp),
)
