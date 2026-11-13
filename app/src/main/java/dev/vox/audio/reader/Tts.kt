package dev.vox.audio.reader

import android.content.Context
import android.media.*
import androidx.compose.runtime.*
import com.k2fsa.sherpa.onnx.*
import dev.vox.audio.data.Http
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File

data class Voice(val id: String, val label: String, val lang: String)

/** Piper voices (VITS) run on-device through sherpa-onnx; each voice is a ~65 MB download, fetched once. */
object Voices {
    val all = listOf(
        Voice("en_US-lessac-medium", "English (US) · Lessac", "en"),
        Voice("en_US-amy-medium", "English (US) · Amy", "en"),
        Voice("en_GB-alba-medium", "English (UK) · Alba", "en"),
        Voice("ro_RO-mihai-medium", "Română · Mihai", "ro"),
        Voice("de_DE-thorsten-medium", "Deutsch · Thorsten", "de"),
        Voice("fr_FR-siwis-medium", "Français · Siwis", "fr"),
        Voice("es_ES-davefx-medium", "Español · Davefx", "es"),
    )
    private lateinit var app: Context
    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext }
    fun forLang(lang: String) = all.firstOrNull { it.lang == lang } ?: all[0]
    fun dir(id: String) = File(app.filesDir, "voices/$id")
    fun installed(id: String) = File(dir(id), ".ok").exists()

    var installing by mutableStateOf<String?>(null); private set
    var progress by mutableFloatStateOf(0f); private set
    var error by mutableStateOf<String?>(null)

    suspend fun install(id: String) = withContext(Dispatchers.IO) {
        if (installed(id)) return@withContext
        installing = id; progress = 0f; error = null
        val tmp = File(app.cacheDir, "$id.tar.bz2")
        try {
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-$id.tar.bz2"
            Http.client.newBuilder().readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
                .newCall(Request.Builder().url(url).header("User-Agent", "Vox").build()).execute().use { r ->
                    if (!r.isSuccessful) error("HTTP ${r.code}")
                    val total = r.body!!.contentLength().coerceAtLeast(1)
                    var done = 0L
                    r.body!!.byteStream().use { i -> tmp.outputStream().use { o ->
                        val b = ByteArray(64 * 1024)
                        while (true) { val n = i.read(b); if (n < 0) break; o.write(b, 0, n); done += n; progress = done * 0.8f / total }
                    } }
                }
            val out = dir(id).apply { deleteRecursively(); mkdirs() }
            TarArchiveInputStream(BZip2CompressorInputStream(tmp.inputStream().buffered())).use { tar ->
                var n = 0
                while (true) {
                    val e = tar.nextEntry ?: break
                    val rel = e.name.substringAfter('/', "")
                    if (rel.isEmpty() || e.isDirectory) continue
                    File(out, rel).also { it.parentFile!!.mkdirs() }.outputStream().use { tar.copyTo(it) }
                    if (++n % 40 == 0) progress = (0.8f + n / 3000f).coerceAtMost(0.99f)
                }
            }
            File(out, ".ok").writeText("1"); progress = 1f
        } catch (e: Exception) { error = "Voice download failed: ${e.message}"; dir(id).deleteRecursively() }
        finally { tmp.delete(); installing = null }
    }

    private var engine: Pair<String, OfflineTts>? = null
    @Synchronized fun engine(id: String): OfflineTts {
        engine?.takeIf { it.first == id }?.let { return it.second }
        engine?.second?.release()
        val root = dir(id)
        val onnx = root.listFiles { f -> f.name.endsWith(".onnx") }!!.first()
        val vits = OfflineTtsVitsModelConfig(model = onnx.path, tokens = File(root, "tokens.txt").path, dataDir = File(root, "espeak-ng-data").path)
        val cfg = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = 4, debug = false, provider = "cpu"))
        return OfflineTts(config = cfg).also { engine = id to it }
    }
}

data class Sent(val start: Int, val end: Int)

object Sentences {
    private val abbr = setOf("mr", "mrs", "ms", "dr", "st", "prof", "sr", "jr", "vs", "etc", "no", "dl", "dna")
    private val end = Regex("""([.!?…]+["'”’)\]]*)(\s+)|\n""")

    fun split(t: String): List<Sent> {
        val out = ArrayList<Sent>(); var s = 0
        fun add(a: Int, b: Int) {
            var x = a; var y = b
            while (x < y && t[x].isWhitespace()) x++
            while (y > x && t[y - 1].isWhitespace()) y--
            if (y - x < 1 || (x until y).none { t[it].isLetterOrDigit() }) return
            var p = x
            while (y - p > 260) {          // very long sentence: break at a comma/semicolon near the middle
                val cut = (p + 120..minOf(p + 240, y - 1)).firstOrNull { t[it] == ',' || t[it] == ';' || t[it] == ':' }
                    ?: break
                out.add(Sent(p, cut + 1)); p = cut + 1
                while (p < y && t[p].isWhitespace()) p++
            }
            out.add(Sent(p, y))
        }
        for (m in end.findAll(t)) {
            if (m.value != "\n") {
                val word = t.substring(maxOf(s, m.range.first - 6), m.range.first).takeLastWhile { it.isLetter() }.lowercase()
                if (m.groupValues[1] == "." && word in abbr) continue
                add(s, m.range.first + m.groupValues[1].length)
            } else add(s, m.range.first)
            s = m.range.last + 1
        }
        add(s, t.length)
        return out
    }
}

private fun FloatArray.toPcm() = ShortArray(size) { (this[it].coerceIn(-1f, 1f) * 32767f).toInt().toShort() }

/** Reads an open ebook aloud sentence by sentence; `cursor` is the sentence being spoken (chapter + char range). */
object Narrator {
    enum class S { Idle, Loading, Playing, Paused }
    var state by mutableStateOf(S.Idle); private set
    var chapter by mutableIntStateOf(-1); private set
    var cursor by mutableStateOf<IntRange?>(null); private set
    var bookId by mutableStateOf<String?>(null); private set
    var title by mutableStateOf(""); private set
    var speed by mutableFloatStateOf(1f)
    var message by mutableStateOf<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var gen = 0                       // identifies the current run; a stale run must not touch shared state
    private var track: AudioTrack? = null
    private val paused = MutableStateFlow(false)
    private lateinit var audio: AudioManager
    private var focus: AudioFocusRequest? = null

    private class Chunk(val chapter: Int, val range: IntRange, val pcm: ShortArray)

    fun start(ctx: Context, book: EBook, plain: List<String>, fromChapter: Int, fromOffset: Int, voiceId: String) {
        val old = job
        stopInternal()
        val my = ++gen
        val app = ctx.applicationContext
        audio = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        bookId = book.id; title = book.title; state = S.Loading; message = null; paused.value = false
        NarrationService.start(app)
        job = scope.launch {
            old?.join()                         // the previous run must finish releasing its AudioTrack before we create ours
            try { run(app, plain, fromChapter, fromOffset, voiceId) }
            catch (e: CancellationException) { throw e }
            catch (e: Throwable) { if (gen == my) message = "Narration failed: ${e.message}" }
            finally { release(); if (gen == my) { state = S.Idle; cursor = null } }
        }
    }

    private suspend fun run(ctx: Context, plain: List<String>, fromChapter: Int, fromOffset: Int, voiceId: String) = coroutineScope {
        val tts = Voices.engine(voiceId)
        val rate = tts.sampleRate()
        val minBuf = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(maxOf(minBuf, rate)).setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setOnAudioFocusChangeListener { c -> if (c == AudioManager.AUDIOFOCUS_LOSS || c == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) pause() }.build()
        audio.requestAudioFocus(focus!!)

        val q = Channel<Chunk>(3)
        val producer = launch {
            for (ci in fromChapter until plain.size) {
                val text = plain[ci]
                for (s in Sentences.split(text)) {
                    if (ci == fromChapter && s.end <= fromOffset) continue
                    val t0 = System.nanoTime()
                    val g = tts.generate(text.substring(s.start, s.end).replace('\n', ' '), 0, speed)
                    android.util.Log.d("VoxTts", "chapter $ci ${s.start}-${s.end}: ${g.samples.size * 1000L / rate} ms audio in ${(System.nanoTime() - t0) / 1_000_000} ms")
                    val gap = ShortArray(rate * 12 / 100)
                    q.send(Chunk(ci, s.start until s.end, g.samples.toPcm() + gap))
                }
            }
            q.close()
        }
        t.play()
        try {
            for (c in q) {
                chapter = c.chapter; cursor = c.range
                var o = 0; val slice = rate / 4
                while (o < c.pcm.size) {
                    if (paused.value) { t.pause(); state = S.Paused; paused.first { !it }; t.play() }
                    state = S.Playing
                    val n = minOf(slice, c.pcm.size - o)
                    t.write(c.pcm, o, n); o += n
                }
            }
            t.stop()
        } finally { producer.cancel() }
    }

    fun pause() { paused.value = true; track?.pause(); if (state == S.Playing) state = S.Paused }
    fun resume() { paused.value = false }
    fun toggle() { if (state == S.Playing) pause() else if (state == S.Paused) resume() }
    fun stop() { stopInternal() }

    private fun stopInternal() {
        paused.value = false
        runCatching { track?.pause(); track?.flush() }
        job?.cancel(); job = null
    }

    private fun release() {
        runCatching { track?.release() }; track = null
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }; focus = null
    }
}

/** Encodes PCM to AAC in an .m4a container. */
class AacWriter(path: String, private val rate: Int) {
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
    private val muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private var track = -1
    private var samples = 0L
    private val info = MediaCodec.BufferInfo()
    val seconds get() = (samples / rate).toInt()
    val ms get() = samples * 1000 / rate

    init {
        val f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 56_000); setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
        }
        codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE); codec.start()
    }

    fun write(pcm: ShortArray) {
        var o = 0
        while (o < pcm.size) {
            val i = codec.dequeueInputBuffer(10_000)
            if (i < 0) { drain(); continue }
            val buf = codec.getInputBuffer(i)!!; buf.clear()
            val n = minOf(pcm.size - o, buf.capacity() / 2)
            for (k in 0 until n) buf.putShort(pcm[o + k])
            codec.queueInputBuffer(i, 0, n * 2, samples * 1_000_000L / rate, 0)
            samples += n; o += n
            drain()
        }
    }

    private fun drain(end: Boolean = false) {
        while (true) {
            val i = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> { track = muxer.addTrack(codec.outputFormat); muxer.start() }
                i >= 0 -> {
                    val b = codec.getOutputBuffer(i)!!
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && track >= 0) muxer.writeSampleData(track, b, info)
                    codec.releaseOutputBuffer(i, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
                else -> if (!end) return
            }
        }
    }

    fun finish() {
        var i = codec.dequeueInputBuffer(1_000_000)
        if (i >= 0) codec.queueInputBuffer(i, 0, 0, samples * 1_000_000L / rate, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        runCatching { codec.stop() }; codec.release()
        runCatching { muxer.stop() }; muxer.release()
    }
}

/**
 * Turns an ebook into an audiobook, one chapter at a time. Each finished chapter is written to book.json straight away,
 * so the book is playable after the first chapter while the rest keeps converting (and an interrupted run resumes).
 */
object Converter {
    var bookId by mutableStateOf<String?>(null); private set      // book being converted right now
    var readyId by mutableStateOf<String?>(null); private set     // book whose `ready` chapters can be played
    var ready by mutableIntStateOf(0); private set
    var total by mutableIntStateOf(0); private set
    var progress by mutableFloatStateOf(0f); private set
    var status by mutableStateOf<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    val running get() = job?.isActive == true

    fun audiobookDir(ctx: Context, id: String) = File(ctx.filesDir, "audiobooks/$id")

    private fun writeJson(dir: File, book: EBook, cover: String?, chapters: org.json.JSONArray) {
        val tmp = File(dir, "book.json.tmp")
        tmp.writeText(org.json.JSONObject().put("id", book.id).put("title", book.title).put("author", book.author)
            .put("cover", cover ?: "").put("total", book.chapters.size).put("chapters", chapters).toString())
        tmp.renameTo(File(dir, "book.json"))
    }

    fun start(ctx: Context, book: EBook, voiceId: String, onChapter: () -> Unit) {
        if (running) return
        val app = ctx.applicationContext
        bookId = book.id; progress = 0f; status = "Preparing voice…"; total = book.chapters.size
        NarrationService.start(app)
        job = scope.launch {
            try {
                val tts = Voices.engine(voiceId); val rate = tts.sampleRate()
                val dir = audiobookDir(app, book.id).apply { mkdirs() }
                val cover = Ebooks.coverFile(book.id)?.let { "file://${it.path}" }
                // resume: keep chapters that are already complete
                val chapters = org.json.JSONArray()
                runCatching {
                    val old = org.json.JSONObject(File(dir, "book.json").readText()).getJSONArray("chapters")
                    for (i in 0 until old.length()) if (File(old.getJSONObject(i).getString("file")).exists()) chapters.put(old.getJSONObject(i)) else break
                }
                val totalChars = book.chapters.sumOf { c -> c.paras.sumOf { it.length } + c.title.length }.coerceAtLeast(1)
                var done = book.chapters.take(chapters.length()).sumOf { c -> c.paras.sumOf { it.length } + c.title.length }
                readyId = book.id; ready = chapters.length()
                for (ci in chapters.length() until book.chapters.size) {
                    ensureActive()
                    val ch = book.chapters[ci]
                    val text = ch.title + "\n \n" + ch.paras.joinToString("\n") + "\n"
                    val f = File(dir, "%03d.m4a".format(ci + 1)); val w = AacWriter(f.path, rate)
                    status = "Chapter ${ci + 1} of ${book.chapters.size}"
                    val map = ArrayList<LongArray>()
                    for (s in Sentences.split(text)) {
                        ensureActive()
                        map.add(longArrayOf(w.ms, s.start.toLong(), s.end.toLong()))
                        w.write(tts.generate(text.substring(s.start, s.end).replace('\n', ' '), 0, 1f).samples.toPcm())
                        w.write(ShortArray(rate * 15 / 100))
                        done += s.end - s.start; progress = (done.toFloat() / totalChars).coerceAtMost(1f)
                    }
                    val secs = w.seconds; w.finish()
                    SyncMap.write(SyncMap.file(app, book.id, ci), map)
                    chapters.put(org.json.JSONObject().put("title", ch.title).put("file", f.path).put("sec", secs))
                    writeJson(dir, book, cover, chapters)
                    ready = chapters.length()
                    withContext(Dispatchers.Main) { onChapter() }
                }
                status = "Done"; progress = 1f
            } catch (e: CancellationException) { status = "Paused: reopen the book and tap convert to continue"
            } catch (e: Throwable) { status = "Conversion failed: ${e.message}"
            } finally { bookId = null }
        }
    }

    fun cancel() { job?.cancel() }
}
