package com.cineo.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
import com.google.android.gms.cast.framework.CastSession
import com.google.android.gms.cast.framework.SessionManagerListener
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
        // Le Chromecast lit via le relais du téléphone, qui se reconnecte quand le serveur IPTV coupe (erreur 103)
        Thread {
            val final = CastRelay.url(c, u) ?: u
            val info = MediaInfo.Builder(final).setContentType(type).setMetadata(meta)
                .setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED).build()
            Handler(Looper.getMainLooper()).post {
                client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
                    .setResultCallback { r -> if (!r.status.isSuccess) toast(c, "Chromecast : échec du chargement (${r.status.statusCode})") }
                if (watched !== client) { watched = client; client.registerCallback(errCb) }
                if (!ended) { ended = true; context(c)?.sessionManager?.addSessionManagerListener(endCb, CastSession::class.java) }
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

    private var ended = false
    /** Fin de la session Chromecast : on arrête le relais. */
    private val endCb = object : SessionManagerListener<CastSession> {
        override fun onSessionEnded(s: CastSession, e: Int) = CastRelayService.stop(app)
        override fun onSessionStarting(s: CastSession) {}
        override fun onSessionStarted(s: CastSession, id: String) {}
        override fun onSessionStartFailed(s: CastSession, e: Int) {}
        override fun onSessionEnding(s: CastSession) {}
        override fun onSessionResuming(s: CastSession, id: String) {}
        override fun onSessionResumed(s: CastSession, wasSuspended: Boolean) {}
        override fun onSessionResumeFailed(s: CastSession, e: Int) {}
        override fun onSessionSuspended(s: CastSession, reason: Int) {}
    }
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
