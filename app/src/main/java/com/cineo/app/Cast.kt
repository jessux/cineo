package com.cineo.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.mediarouter.app.MediaRouteButton
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadRequestData
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.MediaStatus
import com.google.android.gms.cast.framework.media.RemoteMediaClient
import com.google.android.gms.cast.framework.CastButtonFactory
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.google.android.gms.cast.framework.media.CastMediaOptions
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
        val u = if (live) url.removeSuffix(".ts") + ".m3u8" else url
        val meta = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
            putString(MediaMetadata.KEY_TITLE, title)
            image?.let { addImage(WebImage(Uri.parse(it))) }
        }
        val type = when (u.substringAfterLast('.').lowercase()) {
            "m3u8" -> "application/x-mpegURL"; "mkv" -> "video/x-matroska"; "webm" -> "video/webm"
            "ts" -> "video/mp2t"; "avi" -> "video/x-msvideo"; else -> "video/mp4"
        }
        val info = MediaInfo.Builder(u).setContentType(type).setMetadata(meta)
            .setStreamType(if (live) MediaInfo.STREAM_TYPE_LIVE else MediaInfo.STREAM_TYPE_BUFFERED).build()
        client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true).build())
        watch(client, info, live)
        c.startActivity(Intent(c, CastControllerActivity::class.java))
        return true
    }

    private var cb: RemoteMediaClient.Callback? = null
    private var progress: RemoteMediaClient.ProgressListener? = null

    /** Relance automatiquement le flux (à la dernière position) si le serveur coupe la lecture. */
    private fun watch(client: RemoteMediaClient, info: MediaInfo, live: Boolean) {
        cb?.let { client.unregisterCallback(it) }
        progress?.let { client.removeProgressListener(it) }
        var pos = 0L; var dur = 0L; var retries = 0
        val h = Handler(Looper.getMainLooper())
        progress = RemoteMediaClient.ProgressListener { p, d -> if (p > 0) { pos = p; dur = d; if (p > 30_000) retries = 0 } }
            .also { client.addProgressListener(it, 1000) }
        cb = object : RemoteMediaClient.Callback() {
            override fun onStatusUpdated() {
                val st = client.mediaStatus ?: return
                if (st.mediaInfo?.contentId?.let { it != info.contentId } == true) return
                if (st.playerState != MediaStatus.PLAYER_STATE_IDLE) return
                val cut = when (st.idleReason) {
                    MediaStatus.IDLE_REASON_ERROR -> true
                    MediaStatus.IDLE_REASON_FINISHED -> live || (dur > 0 && pos < dur - 60_000)
                    else -> false
                }
                if (!cut || retries >= 5) return
                retries++
                h.postDelayed({
                    client.load(MediaLoadRequestData.Builder().setMediaInfo(info).setAutoplay(true)
                        .apply { if (!live) setCurrentTime(pos) }.build())
                }, 2000L * retries)
            }
        }.also { client.registerCallback(it) }
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
