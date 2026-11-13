package dev.vox.audio.play

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.vox.audio.MainActivity
import dev.vox.audio.data.Http
import dev.vox.audio.data.YT

/** Keeps playback alive in the background with lock-screen / Bluetooth / notification controls. */
class PlaybackService : MediaSessionService() {
    companion object { @Volatile var exo: ExoPlayer? = null }   // same process as the UI; touched on the main thread only
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        YT.attach(this)
        val http = OkHttpDataSource.Factory(Http.client)
            .setUserAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36")
        // yt://VIDEOID -> real (expiring) YouTube audio URL, resolved lazily via yt-dlp when playback starts
        val resolving = ResolvingDataSource.Factory(http) { spec ->
            if (spec.uri.scheme == "yt") spec.withUri(Uri.parse(YT.streamUrl(spec.uri.schemeSpecificPart.trim('/')))) else spec
        }
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder().setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).setUsage(C.USAGE_MEDIA).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(15_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player.skipSilenceEnabled = dev.vox.audio.data.Store(this).skipSilence
        exo = player
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(open).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        exo = null
        session?.run { player.release(); release() }
        session = null
        super.onDestroy()
    }
}
