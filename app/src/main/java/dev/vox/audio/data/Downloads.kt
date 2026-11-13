package dev.vox.audio.data

import android.content.Context
import androidx.compose.runtime.*
import dev.vox.audio.reader.NarrationService
import kotlinx.coroutines.*
import okhttp3.Request
import java.io.File

/** Offline copies of streamed audiobooks (plain https chapters). Playback prefers the local file automatically. */
object Downloads {
    var active by mutableStateOf<String?>(null); private set          // book key being downloaded
    var progress by mutableFloatStateOf(0f); private set
    var message by mutableStateOf<String?>(null)
    var version by mutableIntStateOf(0); private set                    // bumps when something is added/removed
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun dir(ctx: Context, key: String) = File(ctx.filesDir, "offline/" + key.replace(Regex("[^A-Za-z0-9._-]"), "_"))
    private fun file(ctx: Context, key: String, i: Int) = File(dir(ctx, key), "%04d.audio".format(i))

    fun canDownload(b: Book) = b.chapters.isNotEmpty() && b.chapters.all { it.url.startsWith("http") && !it.url.startsWith("http://127.0.0.1") }
    fun isDownloaded(ctx: Context, b: Book): Boolean { version; return b.chapters.isNotEmpty() && File(dir(ctx, b.key), ".ok").exists() }
    fun sizeMb(ctx: Context, b: Book): Long { version; return dir(ctx, b.key).walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1_048_576 }

    /** file:// URL when a chapter exists offline, otherwise the original stream. */
    fun resolve(ctx: Context, key: String, i: Int, url: String): String =
        file(ctx, key, i).takeIf { it.length() > 0 && File(dir(ctx, key), ".ok").exists() }?.let { "file://${it.path}" } ?: url

    fun remove(ctx: Context, b: Book) { dir(ctx, b.key).deleteRecursively(); version++ }
    fun cancel() { job?.cancel() }

    fun start(ctx: Context, b: Book) {
        if (active != null || !canDownload(b)) return
        val app = ctx.applicationContext
        val free = android.os.StatFs(app.filesDir.path).availableBytes
        if (free < 400L * 1024 * 1024) { message = "Not enough free storage (${free / 1_048_576} MB)"; return }
        active = b.key; progress = 0f; message = null
        NarrationService.start(app)
        job = scope.launch {
            try {
                val d = dir(app, b.key).apply { mkdirs() }
                b.chapters.forEachIndexed { i, ch ->
                    ensureActive()
                    val f = file(app, b.key, i)
                    if (f.length() == 0L) {
                        val tmp = File(f.path + ".part")
                        Http.client.newCall(Request.Builder().url(ch.url).header("User-Agent", "Vox").build()).execute().use { r ->
                            if (!r.isSuccessful) error("HTTP ${r.code}")
                            val total = r.body!!.contentLength().takeIf { it > 0 } ?: 1L
                            var done = 0L
                            r.body!!.byteStream().use { inp -> tmp.outputStream().use { o ->
                                val buf = ByteArray(64 * 1024)
                                while (true) {
                                    val n = inp.read(buf); if (n < 0) break
                                    o.write(buf, 0, n); done += n
                                    progress = ((i + (done.toFloat() / total).coerceAtMost(1f)) / b.chapters.size)
                                    ensureActive()
                                }
                            } }
                        }
                        tmp.renameTo(f)
                    }
                    progress = (i + 1f) / b.chapters.size
                }
                File(d, ".ok").writeText("1"); message = null
            } catch (e: CancellationException) { message = "Download paused"
            } catch (e: Throwable) { message = "Download failed: ${e.message}"
            } finally { active = null; version++ }
        }
    }
}
