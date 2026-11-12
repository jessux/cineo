package com.cineo.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/** Un élément de la file de lecture. keys = clés de reprise (vides pour le direct). */
data class PlayItem(val url: String, val title: String, val image: String?, val keys: List<String> = emptyList(), val ep: String = "")

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null
    private lateinit var queue: List<PlayItem>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val urls = intent.getStringArrayExtra("urls").orEmpty()
        val titles = intent.getStringArrayExtra("titles").orEmpty()
        val keys = intent.getStringArrayExtra("keys").orEmpty()
        val eps = intent.getStringArrayExtra("eps").orEmpty()
        queue = urls.indices.map { i ->
            PlayItem(urls[i], titles.getOrElse(i) { "" }, null, keys.getOrElse(i) { "" }.split(";").filter { it.isNotBlank() }, eps.getOrElse(i) { "" })
        }

        val http = DefaultHttpDataSource.Factory().setUserAgent(Xtream.UA).setAllowCrossProtocolRedirects(true)
        // FFmpeg pour l'audio (AC3, E-AC3, DTS, TrueHD…) + bascule vers un autre décodeur si le premier échoue
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        player = p
        // File de lecture : les épisodes suivants s'enchaînent automatiquement
        p.setMediaItems(queue.map {
            MediaItem.Builder().setUri(it.url).setMediaMetadata(MediaMetadata.Builder().setTitle(it.title).build()).build()
        }, 0, queue.firstOrNull()?.keys?.firstOrNull()?.let { Resume.get(this, it)?.pos } ?: 0L)

        var last = 0
        p.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // épisode précédent terminé : on efface sa reprise
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) queue.getOrNull(last)?.let { Resume.put(this@PlayerActivity, it.keys.toTypedArray(), 1, 1, it.ep) }
                last = p.currentMediaItemIndex
            }
            override fun onPlayerError(error: PlaybackException) = onError(error, queue.getOrNull(p.currentMediaItemIndex)?.url.orEmpty())
        })
        p.prepare()
        p.playWhenReady = true

        val view = PlayerView(this).apply {
            player = p
            val many = queue.size > 1
            setShowNextButton(many); setShowPreviousButton(many)
            setShowSubtitleButton(true) // sous-titres ; la roue dentée donne la piste audio et la vitesse
            keepScreenOn = true
        }
        // Bouton format d'image : Adapter → Zoom → Étirer
        val modes = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to "Adapter",
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to "Zoom",
            AspectRatioFrameLayout.RESIZE_MODE_FILL to "Étirer",
        )
        var mode = 0
        val ratio = ImageButton(this).apply {
            setImageResource(android.R.drawable.ic_menu_crop)
            setBackgroundColor(0x66000000)
            setOnClickListener {
                mode = (mode + 1) % modes.size
                view.resizeMode = modes[mode].first
                Toast.makeText(this@PlayerActivity, modes[mode].second, Toast.LENGTH_SHORT).show()
            }
        }
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { ratio.visibility = it })
        setContentView(FrameLayout(this).apply {
            addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val px = (48 * resources.displayMetrics.density).toInt()
            addView(ratio, FrameLayout.LayoutParams(px, px, Gravity.TOP or Gravity.END).apply { setMargins(px / 3, px / 3, px / 3, px / 3) })
        })
    }

    private fun onError(error: PlaybackException, url: String) {
        val code = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        if (code == 401 || code == 403 || code == 458) {
            Toast.makeText(this, "Accès refusé par le serveur : l'abonnement est probablement déjà utilisé sur un autre appareil.", Toast.LENGTH_LONG).show()
            finish(); return
        }
        // Format non lisible par le téléphone (souvent vidéo HEVC 10 bits) : on passe la main à VLC
        val detail = (error.cause?.message ?: error.message ?: "").let { m ->
            Regex("(video|audio)/[\\w.-]+").findAll(m).map { it.value }.distinct().joinToString(", ")
        }.ifBlank { error.errorCodeName }
        val vlc = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").setPackage("org.videolan.vlc")
        if (vlc.resolveActivity(packageManager) != null) {
            Toast.makeText(this, "Format non pris en charge ($detail) : ouverture dans VLC", Toast.LENGTH_LONG).show()
            startActivity(vlc)
        } else {
            Toast.makeText(this, "Format non pris en charge ($detail). Installez VLC pour lire ce contenu.", Toast.LENGTH_LONG).show()
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.videolan.vlc"))) }
        }
        finish()
    }

    override fun onStop() {
        super.onStop()
        player?.let { p ->
            val cur = queue.getOrNull(p.currentMediaItemIndex)
            if (cur != null && cur.keys.isNotEmpty() && !p.isCurrentMediaItemLive)
                Resume.put(this, cur.keys.toTypedArray(), p.currentPosition, p.duration, cur.ep)
            p.pause()
        }
    }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    companion object {
        fun start(ctx: Context, queue: List<PlayItem>, live: Boolean = false) {
            val first = queue.firstOrNull() ?: return
            if (Cast.play(ctx, first.url, first.title, first.image, live)) return
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java)
                .putExtra("urls", queue.map { it.url }.toTypedArray())
                .putExtra("titles", queue.map { it.title }.toTypedArray())
                .putExtra("keys", queue.map { it.keys.joinToString(";") }.toTypedArray())
                .putExtra("eps", queue.map { it.ep }.toTypedArray()))
        }
    }
}
