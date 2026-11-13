package dev.vox.audio.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.libtorrent4j.*
import org.libtorrent4j.alerts.Alert
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

data class TorrentFiles(val name: String, val chapters: List<Chapter>)

/**
 * Torrent engine (libtorrent4j). Resolves a magnet / infoHash / .torrent URL into audio "chapters" and
 * streams them sequentially through a loopback HTTP server (with Range support) so the normal player can play
 * a file while its pieces are still downloading.
 */
object Torrents {
    private lateinit var dir: File
    private val sm = SessionManager()
    private val infos = ConcurrentHashMap<String, TorrentInfo>()
    private var server: ServerSocket? = null
    private val audioExt = setOf("mp3", "m4a", "m4b", "ogg", "opus", "flac", "aac", "wav")

    fun init(ctx: Context) {
        if (!::dir.isInitialized) dir = File(ctx.cacheDir, "torrents").apply { mkdirs() }
    }

    fun cacheBytes(): Long = if (::dir.isInitialized) dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L

    fun clearCache() {
        runCatching { sm.pause() }
        infos.keys.forEach { runCatching { sm.find(Sha1Hash.parseHex(it))?.let { h -> sm.remove(h) } } }
        infos.clear()
        if (::dir.isInitialized) dir.listFiles()?.forEach { it.deleteRecursively() }
        runCatching { sm.resume() }
    }

    @Synchronized
    private fun start() {
        if (!sm.isRunning) {
            sm.addListener(object : AlertListener {
                override fun types() = null
                override fun alert(a: Alert<*>) {
                    val m = a.message()
                    if (Regex("error|fail|web seed", RegexOption.IGNORE_CASE).containsMatchIn(m)) android.util.Log.d("VoxTorrent", m)
                }
            })
            // Android 11+ forbids enumerating interfaces (enum_route), so bind explicitly
            val sp = SettingsPack().listenInterfaces("0.0.0.0:6881")
            sm.start(SessionParams(sp))
            sm.applySettings(sp)
            android.util.Log.d("VoxTorrent", "listen_interfaces=" + sm.settings().listenInterfaces())
        }
        if (server == null) {
            val ss = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
            server = ss
            thread(isDaemon = true, name = "vox-torrent-http") {
                while (!ss.isClosed) {
                    val c = runCatching { ss.accept() }.getOrNull() ?: break
                    thread(isDaemon = true) { runCatching { serve(c) }.onFailure { android.util.Log.d("VoxTorrent", "serve failed: $it") } }
                }
            }
        }
    }

    private fun natural(s: String) = s.lowercase().replace(Regex("\\d+")) { it.value.padStart(8, '0') }

    suspend fun resolve(ref: String): TorrentFiles = withContext(Dispatchers.IO) {
        start()
        val ti = loadInfo(ref)
        val hex = ti.infoHash().toHex()
        infos[hex] = ti
        if (sm.find(ti.infoHash()) == null) sm.download(ti, dir)
        val h = sm.find(ti.infoHash()) ?: error("Could not start torrent")
        val fs = ti.files()
        val all = (0 until fs.numFiles()).filter { fs.filePath(it).substringAfterLast('.', "").lowercase() in audioExt }
        // many torrents ship the same audio in several formats/bitrates: keep one tier (mp3 > m4a/m4b > rest, full quality > 64kb)
        fun ext(i: Int) = fs.filePath(i).substringAfterLast('.').lowercase()
        val fmt = listOf(setOf("mp3"), setOf("m4a", "m4b", "aac")).firstOrNull { f -> all.any { ext(it) in f } }
        val byFmt = if (fmt != null) all.filter { ext(it) in fmt } else all
        val full = byFmt.filter { !fs.filePath(it).contains("64kb", true) }
        val audio = (if (full.isNotEmpty()) full else byFmt).sortedBy { natural(fs.filePath(it)) }
        if (audio.isEmpty()) error("No audio files in this torrent")
        // Keep the first file wanted so the torrent never drops into "finished" (web seeds are not used there)
        h.prioritizeFiles(Array(fs.numFiles()) { if (it == audio.first()) Priority.DEFAULT else Priority.IGNORE })
        h.setFlags(TorrentFlags.SEQUENTIAL_DOWNLOAD)
        val port = server!!.localPort
        TorrentFiles(ti.name(), audio.map {
            Chapter(fs.filePath(it).substringAfterLast('/').substringBeforeLast('.'), "http://127.0.0.1:$port/$hex/$it")
        })
    }

    private suspend fun loadInfo(ref: String): TorrentInfo {
        val r = ref.trim()
        if (r.startsWith("http://") || r.startsWith("https://")) return TorrentInfo.bdecode(Http.getBytes(r))
        val magnet = if (Regex("^[0-9a-fA-F]{40}$").matches(r)) "magnet:?xt=urn:btih:$r" else r
        val bytes = sm.fetchMagnet(magnet, 45, dir) ?: error("No metadata received (no peers found)")
        return TorrentInfo.bdecode(bytes)
    }

    /** True when this loopback chapter URL belongs to the torrent session that is alive right now (ports change per launch). */
    fun knows(url: String): Boolean {
        val rest = url.removePrefix("http://127.0.0.1:").takeIf { it != url } ?: return true
        val port = rest.substringBefore('/').toIntOrNull(); val hex = rest.substringAfter('/').substringBefore('/')
        return port != null && port == server?.localPort && infos.containsKey(hex)
    }

    /** e.g. "12 peers · 340 kB/s · 41%" for the torrent behind a chapter URL. */
    fun statusFor(chapterUrl: String?): String? {
        val hex = chapterUrl?.takeIf { it.startsWith("http://127.0.0.1") }?.substringAfter("//")?.split('/')?.getOrNull(1) ?: return null
        val st = runCatching { sm.find(Sha1Hash.parseHex(hex))?.status() }.getOrNull() ?: return null
        return "${st.state().name.lowercase()} · ${st.numPeers()} peers · ${st.downloadRate() / 1024} kB/s · ${(st.progress() * 100).toInt()}%"
    }

    // ---------------- loopback server ----------------
    private fun serve(s: Socket) = s.use {
        val inp = s.getInputStream().bufferedReader()
        val line = inp.readLine() ?: return
        var range: String? = null
        while (true) {
            val l = inp.readLine()
            if (l.isNullOrEmpty()) break
            if (l.startsWith("Range:", true)) range = l.substringAfter(':').trim()
        }
        android.util.Log.d("VoxTorrent", "request: $line range=$range")
        val seg = line.split(" ").getOrNull(1)?.trim('/')?.split('/') ?: return
        val hex = seg[0]; val idx = seg[1].toInt()
        val ti = infos[hex] ?: return
        val h = sm.find(Sha1Hash.parseHex(hex)) ?: return
        val fs = ti.files()
        val size = fs.fileSize(idx)
        var start = 0L; var end = size - 1
        range?.removePrefix("bytes=")?.split('-')?.let {
            it[0].toLongOrNull()?.let { a -> start = a }
            it.getOrNull(1)?.toLongOrNull()?.let { b -> end = minOf(b, size - 1) }
        }
        android.util.Log.d("VoxTorrent", "serving file $idx size=$size start=$start")
        h.filePriority(idx, Priority.TOP_PRIORITY)
        if (idx + 1 < fs.numFiles() && h.filePriority(idx + 1) == Priority.IGNORE) h.filePriority(idx + 1, Priority.DEFAULT)

        val out = s.getOutputStream()
        val status = if (range != null) "206 Partial Content" else "200 OK"
        val cr = if (range != null) "Content-Range: bytes $start-$end/$size\r\n" else ""
        val mime = when (fs.filePath(idx).substringAfterLast('.').lowercase()) {
            "m4a", "m4b", "aac" -> "audio/mp4"; "ogg", "opus" -> "audio/ogg"; "flac" -> "audio/flac"; "wav" -> "audio/wav"; else -> "audio/mpeg"
        }
        out.write("HTTP/1.1 $status\r\n${cr}Content-Type: $mime\r\nContent-Length: ${end - start + 1}\r\nAccept-Ranges: bytes\r\nConnection: close\r\n\r\n".toByteArray())

        val off = fs.fileOffset(idx); val plen = ti.pieceLength().toLong()
        val buf = ByteArray(64 * 1024)
        var pos = start
        var raf: RandomAccessFile? = null
        try {
            while (pos <= end) {
                val piece = ((off + pos) / plen).toInt()
                if (!waitPiece(h, ti, piece)) break
                val pieceEnd = (piece + 1) * plen - off
                val n = minOf(buf.size.toLong(), pieceEnd - pos, end - pos + 1).toInt()
                if (raf == null) raf = RandomAccessFile(File(dir, fs.filePath(idx)), "r")
                raf.seek(pos); raf.readFully(buf, 0, n)
                out.write(buf, 0, n); pos += n
            }
            out.flush()
        } finally { raf?.close() }
    }

    private fun waitPiece(h: TorrentHandle, ti: TorrentInfo, piece: Int): Boolean {
        val deadline = System.currentTimeMillis() + 90_000
        while (System.currentTimeMillis() < deadline) {
            if (h.havePiece(piece)) return true
            for (k in 0..3) if (piece + k < ti.numPieces() && !h.havePiece(piece + k)) h.setPieceDeadline(piece + k, 400 * (k + 1))
            Thread.sleep(250)
        }
        return false
    }
}
