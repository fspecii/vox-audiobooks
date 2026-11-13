package dev.vox.audio.reader

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import dev.vox.audio.MainActivity
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect

/** Foreground anchor so narration / conversion keep running with the screen off. All logic lives in Narrator / Converter. */
class NarrationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        when (i?.action) {
            "toggle" -> Narrator.toggle()
            "stop" -> { Narrator.stop(); Converter.cancel() }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("narration", "Reading aloud", NotificationManager.IMPORTANCE_LOW))
        startForeground(7, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        scope.coroutineContext.cancelChildren()
        scope.launch {
            snapshotFlow { Triple(Narrator.state, Converter.bookId, Converter.status) }.collect { (s, conv, _) ->
                if (s == Narrator.S.Idle && conv == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
                else nm.notify(7, build())
            }
        }
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        fun act(a: String) = PendingIntent.getService(this, a.hashCode(), Intent(this, NarrationService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val converting = Converter.bookId != null
        val b = NotificationCompat.Builder(this, "narration").setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).setContentIntent(open)
            .setContentTitle(if (converting) "Converting to audiobook" else Narrator.title.ifBlank { "Reading aloud" })
            .setContentText(if (converting) "${Converter.status ?: ""} · ${(Converter.progress * 100).toInt()}%" else if (Narrator.state == Narrator.S.Paused) "Paused" else "Reading aloud")
        if (converting) b.setProgress(100, (Converter.progress * 100).toInt(), false)
        else b.addAction(0, if (Narrator.state == Narrator.S.Paused) "Resume" else "Pause", act("toggle"))
        b.addAction(0, "Stop", act("stop"))
        return b.build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, NarrationService::class.java))
    }
}
