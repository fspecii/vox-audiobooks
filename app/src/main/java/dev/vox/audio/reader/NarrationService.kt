package dev.vox.audio.reader

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.compose.runtime.snapshotFlow
import androidx.core.app.NotificationCompat
import dev.vox.audio.MainActivity
import kotlinx.coroutines.*

/** Foreground anchor so the conversion keeps running with the screen off. All logic lives in Converter. */
class NarrationService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(i: Intent?, flags: Int, id: Int): Int {
        if (i?.action == "stop") { Converter.cancel(); dev.vox.audio.data.Downloads.cancel() }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("narration", "Making audiobooks", NotificationManager.IMPORTANCE_LOW))
        startForeground(7, build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        scope.coroutineContext.cancelChildren()
        scope.launch {
            snapshotFlow { Triple(Converter.bookId ?: dev.vox.audio.data.Downloads.active, Converter.status, (Converter.progress * 100).toInt() + (dev.vox.audio.data.Downloads.progress * 100).toInt()) }.collect { (conv, _, _) ->
                if (conv == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() } else nm.notify(7, build())
            }
        }
        return START_NOT_STICKY
    }

    private fun build(): Notification {
        fun act(a: String) = PendingIntent.getService(this, a.hashCode(), Intent(this, NarrationService::class.java).setAction(a), PendingIntent.FLAG_IMMUTABLE)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val dl = dev.vox.audio.data.Downloads.active != null && Converter.bookId == null
        val pct = ((if (dl) dev.vox.audio.data.Downloads.progress else Converter.progress) * 100).toInt()
        return NotificationCompat.Builder(this, "narration").setSmallIcon(android.R.drawable.ic_media_play).setOngoing(true).setContentIntent(open)
            .setContentTitle(if (dl) "Downloading for offline" else "Making your audiobook").setContentText(if (dl) "$pct%" else "${Converter.status ?: ""} · $pct%")
            .setProgress(100, pct, false).addAction(0, "Stop", act("stop")).build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object { fun start(ctx: Context) = ctx.startForegroundService(Intent(ctx, NarrationService::class.java)) }
}
