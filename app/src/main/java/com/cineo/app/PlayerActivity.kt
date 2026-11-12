package com.cineo.app

import android.content.Context
import android.content.Intent
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
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build()
        player = p
        p.setMediaItem(MediaItem.fromUri(intent.getStringExtra("url").orEmpty()))
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Toast.makeText(this@PlayerActivity, "Lecture impossible : ${error.errorCodeName}", Toast.LENGTH_LONG).show()
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

    override fun onStop() { super.onStop(); player?.pause() }
    override fun onDestroy() { player?.release(); player = null; super.onDestroy() }

    companion object {
        fun start(ctx: Context, url: String, title: String = "", image: String? = null, live: Boolean = false) {
            if (Cast.play(ctx, url, title, image, live)) return
            ctx.startActivity(Intent(ctx, PlayerActivity::class.java).putExtra("url", url))
        }
    }
}
