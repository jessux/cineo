package com.cineo.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.annotation.OptIn
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
class PlayerActivity : ComponentActivity() {
    private var player: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val http = DefaultHttpDataSource.Factory().setUserAgent(Xtream.UA).setAllowCrossProtocolRedirects(true)
        // FFmpeg pour l'audio (AC3, E-AC3, DTS, TrueHD…) + bascule vers un autre décodeur si le premier échoue
        val renderers = DefaultRenderersFactory(this)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            .setEnableDecoderFallback(true)
        val p = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        player = p
        val url = intent.getStringExtra("url").orEmpty()
        p.setMediaItem(MediaItem.fromUri(url))
        keys.firstOrNull()?.let { k -> Resume.get(this, k)?.let { p.seekTo(it.pos) } }
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val code = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                if (code == 401 || code == 403 || code == 458) {
                    Toast.makeText(this@PlayerActivity, "Accès refusé par le serveur : l'abonnement est probablement déjà utilisé sur un autre appareil.", Toast.LENGTH_LONG).show()
                    finish(); return
                }
                // Format non lisible par le téléphone (souvent vidéo HEVC 10 bits) : on passe la main à VLC
                val detail = (error.cause?.message ?: error.message ?: "").let { m ->
                    Regex("(video|audio)/[\\w.-]+").findAll(m).map { it.value }.distinct().joinToString(", ")
                }.ifBlank { error.errorCodeName }
                val vlc = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(url), "video/*").setPackage("org.videolan.vlc")
                when {
                    vlc.resolveActivity(packageManager) != null -> {
                        Toast.makeText(this@PlayerActivity, "Format non pris en charge ($detail) : ouverture dans VLC", Toast.LENGTH_LONG).show()
                        startActivity(vlc)
                    }
                    else -> {
                        Toast.makeText(this@PlayerActivity, "Format non pris en charge ($detail). Installez VLC pour lire ce film.", Toast.LENGTH_LONG).show()
                        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=org.videolan.vlc"))) }
                    }
                }
                finish()
            }
        })
        p.prepare()
        p.playWhenReady = true
        setContentView(PlayerView(this).apply {
            player = p
            setShowNextButton(false); setShowPreviousButton(false)
            keepScreenOn = true
        })
    }

    private val keys get() = intent.getStringArrayExtra("keys") ?: emptyArray()

    override fun onStop() {
        super.onStop()
        player?.let { if (keys.isNotEmpty() && !it.isCurrentMediaItemLive) Resume.put(this, keys, it.currentPosition, it.duration, intent.getStringExtra("ep").orEmpty()); it.pause() }
    }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    companion object {
        fun start(ctx: Context, url: String, title: String = "", image: String? = null, live: Boolean = false,
                  keys: Array<String> = emptyArray(), ep: String = "") {
            if (Cast.play(ctx, url, title, image, live)) return
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("url", url).putExtra("keys", keys).putExtra("ep", ep))
        }
    }
}
