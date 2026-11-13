package dev.vox.audio.data

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** yt-dlp (bundled via youtubedl-android) wrapper: search, playlist/video metadata and direct audio stream URLs. */
object YT {
    private lateinit var app: Context
    @Volatile private var ready = false
    // yt-dlp self-update rewrites its files: never overlap it with a running extraction.
    private val lock = ReentrantReadWriteLock()
    private val urlCache = ConcurrentHashMap<String, Pair<Long, String>>()

    fun attach(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }

    @Synchronized
    private fun ensure() {
        if (ready) return
        YoutubeDL.getInstance().init(app)
        ready = true
    }

    /** YouTube changes often; refresh yt-dlp in the background (at most once a day). */
    suspend fun updateIfStale() = withContext(Dispatchers.IO) {
        val p = app.getSharedPreferences("vox", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - p.getLong("ytdlp_update", 0) < 24 * 3600_000L) return@withContext
        runCatching { ensure(); lock.write { YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel.STABLE) } }
            .onSuccess { p.edit().putLong("ytdlp_update", System.currentTimeMillis()).apply() }
    }

    fun json(url: String, vararg opts: String): JSONObject {
        ensure()
        val r = YoutubeDLRequest(url)
        r.addOption("-J"); r.addOption("--flat-playlist"); r.addOption("--no-warnings")
        opts.toList().chunked(2).forEach { r.addOption(it[0], it[1]) }
        return try { JSONObject(lock.read { YoutubeDL.getInstance().execute(r).out }) }
        catch (e: Exception) { throw IOException(clean(e), e) }
    }

    /** yt-dlp errors arrive as full Python tracebacks; keep the useful last line. */
    fun clean(e: Throwable): String {
        val lines = (e.message ?: e.toString()).lines().map { it.trim() }.filter { it.isNotEmpty() }
        return lines.lastOrNull { it.startsWith("ERROR:") } ?: lines.lastOrNull() ?: "yt-dlp failed"
    }

    /** Direct (expiring) audio URL for a video id, cached for 90 min. Called from the player's loading thread. */
    fun streamUrl(videoId: String): String {
        urlCache[videoId]?.let { if (System.currentTimeMillis() - it.first < 90 * 60_000L) return it.second }
        try {
            ensure()
            val r = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId")
            r.addOption("-f", "bestaudio[ext=m4a]/bestaudio/best")
            r.addOption("--no-playlist"); r.addOption("--no-warnings"); r.addOption("-g")
            val url = lock.read { YoutubeDL.getInstance().execute(r).out }.trim().lines().first { it.startsWith("http") }
            urlCache[videoId] = System.currentTimeMillis() to url
            return url
        } catch (e: Exception) { throw IOException(clean(e), e) }
    }
}
