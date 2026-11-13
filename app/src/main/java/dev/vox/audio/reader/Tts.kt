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

/** kind "piper" = Piper VITS voice (one language each); kind "st3" = Supertonic 3 (31 languages, shared download). */
data class Voice(val id: String, val label: String, val lang: String, val kind: String = "piper", val sid: Int = 0, val sizeMb: Int = 65) {
    val assetId get() = if (kind == "st3") "supertonic3" else id
}

/** One loaded model + how to call it. Not thread-safe: use one Synth per worker. */
class Synth(val tts: OfflineTts, val voice: Voice, val lang: String) {
    val rate = tts.sampleRate()
    fun speak(text: String, speed: Float): FloatArray =
        if (voice.kind == "st3") tts.generateWithConfig(text, GenerationConfig(speed = speed, sid = voice.sid, numSteps = 5,
            extra = mapOf("lang" to if (lang in Voices.st3Langs) lang else "en"))).samples
        else tts.generate(text, 0, speed).samples
    fun release() = runCatching { tts.release() }
}

/** Piper voices (VITS) and Supertonic 3 run on-device through sherpa-onnx; each is downloaded once. */
object Voices {
    val all = listOf(
        Voice("en_US-lessac-medium", "English (US) · Lessac", "en"),
        Voice("en_US-amy-medium", "English (US) · Amy", "en"),
        Voice("en_GB-alba-medium", "English (UK) · Alba", "en"),
        Voice("ro_RO-mihai-medium", "Română · Mihai", "ro"),
        Voice("de_DE-thorsten-medium", "Deutsch · Thorsten", "de"),
        Voice("fr_FR-siwis-medium", "Français · Siwis", "fr"),
        Voice("es_ES-davefx-medium", "Español · Davefx", "es"),
        Voice("supertonic3-a", "Supertonic 3 · any language (A)", "*", "st3", 0, 129),
        Voice("supertonic3-b", "Supertonic 3 · any language (B)", "*", "st3", 5, 129),
    )
    val st3Langs = setOf("en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu", "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi")
    private lateinit var app: Context
    fun init(ctx: Context) { if (!::app.isInitialized) app = ctx.applicationContext; Tuner.init(ctx) }
    fun byId(id: String) = all.firstOrNull { it.id == id }
    fun forLang(lang: String) = all.firstOrNull { it.lang == lang } ?: all[0]
    fun dir(id: String) = File(app.filesDir, "voices/${byId(id)?.assetId ?: id}")
    fun installed(id: String) = File(dir(id), ".ok").exists()

    var installing by mutableStateOf<String?>(null); private set
    var progress by mutableFloatStateOf(0f); private set
    var error by mutableStateOf<String?>(null)

    suspend fun install(id: String) = withContext(Dispatchers.IO) {
        if (installed(id)) return@withContext
        installing = id; progress = 0f; error = null
        val tmp = File(app.cacheDir, "${byId(id)?.assetId ?: id}.tar.bz2")
        try {
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/" +
                if (byId(id)?.kind == "st3") "sherpa-onnx-supertonic-3-tts-int8-2026-05-11.tar.bz2" else "vits-piper-$id.tar.bz2"
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

    /** A fresh, independent model instance (use several for parallel generation). */
    fun newSynth(id: String, lang: String, threads: Int): Synth {
        val v = byId(id) ?: error("Unknown voice")
        val root = dir(id)
        val model = if (v.kind == "st3") {
            fun f(n: String) = File(root, n).path
            OfflineTtsModelConfig(supertonic = OfflineTtsSupertonicModelConfig(f("duration_predictor.int8.onnx"), f("text_encoder.int8.onnx"),
                f("vector_estimator.int8.onnx"), f("vocoder.int8.onnx"), f("tts.json"), f("unicode_indexer.bin"), f("voice.bin")),
                numThreads = threads, debug = false, provider = "cpu")
        } else {
            val onnx = root.listFiles { f -> f.name.endsWith(".onnx") }!!.first()
            OfflineTtsModelConfig(vits = OfflineTtsVitsModelConfig(model = onnx.path, tokens = File(root, "tokens.txt").path, dataDir = File(root, "espeak-ng-data").path),
                numThreads = threads, debug = false, provider = "cpu")
        }
        return Synth(OfflineTts(config = OfflineTtsConfig(model = model)), v, lang)
    }

    private var cached: Triple<String, Int, Synth>? = null
    /** Shared instance for live narration (2 threads is the sweet spot for these models unless the speed test says otherwise). */
    @Synchronized fun synth(id: String, lang: String): Synth {
        val th = Tuner.best(byId(id)?.kind ?: "piper")?.threads ?: 2
        cached?.takeIf { it.first == id && it.second == th }?.let { return Synth(it.third.tts, it.third.voice, lang) }
        cached?.third?.release()
        val s = newSynth(id, lang, th); cached = Triple(id, th, s); return s
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
 * Turns an ebook into an audiobook made of PARTS (a few minutes each). Parts are generated starting from wherever you want to listen
 * (then the rest of the book), each one lands in book.json as soon as it is done, and the player appends it to its playlist: one
 * seamless audiobook that is already playable after the first part. An interrupted run resumes.
 */
object Converter {
    var bookId by mutableStateOf<String?>(null); private set      // book being converted right now
    var readyId by mutableStateOf<String?>(null); private set     // book whose `ready` parts can be played
    var ready by mutableIntStateOf(0); private set
    var total by mutableIntStateOf(0); private set
    var progress by mutableFloatStateOf(0f); private set
    var status by mutableStateOf<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    @Volatile private var jump = -1                                // where the user wants audio NOW (overrides the sequential order once)
    val running get() = job?.isActive == true

    fun audiobookDir(ctx: Context, id: String) = File(ctx.filesDir, "audiobooks/$id")
    fun partFile(ctx: Context, id: String, pid: Int) = File(audiobookDir(ctx, id), "%04d.m4a".format(pid + 1))
    fun isReady(ctx: Context, id: String, pid: Int): Boolean =
        runCatching { org.json.JSONObject(File(audiobookDir(ctx, id), "book.json").readText()).getJSONArray("chapters").let { a -> (0 until a.length()).any { a.getJSONObject(it).optInt("pid", -1) == pid } } }.getOrDefault(false)

    private fun writeJson(dir: File, book: EBook, cover: String?, total: Int, parts: List<org.json.JSONObject>) {
        val tmp = File(dir, "book.json.tmp")
        tmp.writeText(org.json.JSONObject().put("v", 2).put("id", book.id).put("title", book.title).put("author", book.author)
            .put("cover", cover ?: "").put("total", total).put("chapters", org.json.JSONArray(parts.sortedBy { it.getInt("pid") })).toString())
        tmp.renameTo(File(dir, "book.json"))
    }

    /** Start converting (or, if this book is already being converted, just move the front of the queue to [fromPart]). */
    fun start(ctx: Context, book: EBook, voiceId: String, fromPart: Int, onPart: () -> Unit) {
        if (running && bookId == book.id) { jump = fromPart; return }
        if (running) return
        val app = ctx.applicationContext
        // nothing left to generate? (every part already exists)
        runCatching {
            val o = org.json.JSONObject(File(audiobookDir(app, book.id), "book.json").readText())
            if (o.optInt("v") == 2 && o.getJSONArray("chapters").length() >= o.getInt("total") && o.getInt("total") == Plan.parts(book).size) return
        }
        bookId = book.id; progress = 0f; status = "Preparing voice…"; jump = -1
        NarrationService.start(app)
        job = scope.launch {
            var pool: List<Synth> = emptyList()
            try {
                val parts = Plan.parts(book)
                total = parts.size
                val dir = audiobookDir(app, book.id)
                // an older (whole-chapter) conversion has a different layout: start clean
                runCatching { if (File(dir, "book.json").exists() && org.json.JSONObject(File(dir, "book.json").readText()).optInt("v") != 2) dir.deleteRecursively() }
                dir.mkdirs()
                val cover = Ebooks.coverFile(book.id)?.let { "file://${it.path}" }
                val done = ArrayList<org.json.JSONObject>()
                runCatching {
                    val old = org.json.JSONObject(File(dir, "book.json").readText()).getJSONArray("chapters")
                    for (i in 0 until old.length()) if (File(old.getJSONObject(i).getString("file")).exists()) done.add(old.getJSONObject(i))
                }
                val doneIds = done.map { it.getInt("pid") }.toMutableSet()
                readyId = book.id; ready = doneIds.size

                val cfg = Tuner.best(Voices.byId(voiceId)?.kind ?: "piper") ?: Tuner.Config(2, 2, 0.0)
                status = "Loading voice…"
                pool = List(cfg.workers) { Voices.newSynth(voiceId, book.lang, cfg.threads) }
                val rate = pool[0].rate
                val free = Channel<Synth>(pool.size); pool.forEach { free.trySend(it) }
                val texts = book.chapters.map { Plan.chapterText(it) }
                val sentences = HashMap<Int, List<Sent>>()
                val totalChars = parts.sumOf { it.end - it.start }.coerceAtLeast(1)
                var doneChars = parts.filter { it.id in doneIds }.sumOf { it.end - it.start }
                var next = fromPart

                while (true) {
                    ensureActive()
                    val hint = if (jump >= 0) jump.also { jump = -1 } else next
                    val pid = (hint until parts.size).firstOrNull { it !in doneIds } ?: (0 until parts.size).firstOrNull { it !in doneIds } ?: break
                    val part = parts[pid]
                    val text = texts[part.chapter]
                    val sents = sentences.getOrPut(part.chapter) { Sentences.split(text) }.filter { it.start >= part.start && it.end <= part.end }
                    val t0 = System.currentTimeMillis()
                    val f = partFile(app, book.id, pid); val w = AacWriter(f.path, rate)
                    status = "Part ${doneIds.size + 1} of ${parts.size}"
                    android.util.Log.d("VoxConv", "start part ${pid + 1} (chapter ${part.chapter}) sentences=${sents.size} hint=$hint")
                    val map = ArrayList<LongArray>()
                    var k = 0
                    while (k < sents.size) {
                        ensureActive()
                        val batch = sents.subList(k, minOf(k + cfg.workers * 3, sents.size)); k += batch.size
                        val pcms = coroutineScope {
                            batch.map { s -> async(Dispatchers.Default) {
                                val sy = free.receive()
                                try { sy.speak(text.substring(s.start, s.end).replace('\n', ' '), 1f).toPcm() } finally { free.send(sy) }
                            } }.awaitAll()
                        }
                        batch.forEachIndexed { j, s ->
                            map.add(longArrayOf(w.ms, s.start.toLong(), s.end.toLong()))
                            w.write(pcms[j]); w.write(ShortArray(rate * 15 / 100))
                            doneChars += s.end - s.start; progress = (doneChars.toFloat() / totalChars).coerceAtMost(1f)
                        }
                    }
                    val secs = w.seconds; w.finish()
                    android.util.Log.d("VoxConv", "part ${pid + 1}/${parts.size}: ${secs}s audio in ${(System.currentTimeMillis() - t0) / 1000}s with ${cfg.workers}x${cfg.threads}")
                    SyncMap.write(SyncMap.file(app, book.id, pid), map)
                    done.add(org.json.JSONObject().put("pid", pid).put("ch", part.chapter).put("title", part.title).put("file", f.path).put("sec", secs))
                    doneIds.add(pid); next = pid + 1
                    writeJson(dir, book, cover, parts.size, done)
                    ready = doneIds.size
                    withContext(Dispatchers.Main) { onPart() }
                }
                status = "Done"; progress = 1f
            } catch (e: CancellationException) { status = "Paused: tap Listen to continue"
            } catch (e: Throwable) { status = "Conversion failed: ${e.message}"
            } finally { pool.forEach { it.release() }; bookId = null }
        }
    }

    fun cancel() { job?.cancel() }
}

/** Measures this phone: which number of parallel model instances x threads synthesises fastest. Result is kept per engine kind. */
object Tuner {
    data class Config(val workers: Int, val threads: Int, val xrt: Double)
    var status by mutableStateOf<String?>(null); private set
    var running by mutableStateOf(false); private set
    private lateinit var store: dev.vox.audio.data.Store
    fun init(ctx: Context) { if (!::store.isInitialized) store = dev.vox.audio.data.Store(ctx.applicationContext) }

    fun best(kind: String): Config? = if (!::store.isInitialized) null else store.tuneRaw(kind)?.split(':')?.let {
        runCatching { Config(it[0].toInt(), it[1].toInt(), it[2].toDouble()) }.getOrNull()
    }

    private val en = listOf("It was a bright cold day in April, and the clocks were striking thirteen.",
        "Winston Smith slipped quickly through the glass doors of Victory Mansions, though not quickly enough to prevent a swirl of gritty dust from entering along with him.",
        "The hallway smelt of boiled cabbage and old rag mats, and at one end of it a coloured poster had been tacked to the wall.")
    private val ro = listOf("Era o zi rece și senină de aprilie, iar ceasurile băteau ora treisprezece.",
        "Winston Smith se strecură repede prin ușile de sticlă ale clădirii, dar nu destul de repede ca să nu intre odată cu el un vârtej de praf.",
        "Holul mirosea a varză fiartă și a covoare vechi, iar la capătul lui un afiș colorat fusese bătut în cuie pe perete.")

    suspend fun run(voiceId: String) = withContext(Dispatchers.Default) {
        val v = Voices.byId(voiceId) ?: return@withContext
        if (running) return@withContext
        running = true
        try {
            val lang = if (v.lang == "*") "en" else v.lang
            val text = if (lang == "ro") ro else en
            val cores = Runtime.getRuntime().availableProcessors()
            val plan = listOf(2 to listOf(1, 2, 3, 4), 1 to listOf(4, 6, 8), 4 to listOf(1, 2)).map { (th, ws) -> th to ws.filter { it * th <= cores + 2 } }
            var bestCfg: Config? = null
            for ((th, workers) in plan) {
                if (workers.isEmpty()) continue
                status = "Loading ${workers.max()} × $th-thread models…"
                val engines = List(workers.max()) { Voices.newSynth(voiceId, lang, th) }
                engines.forEach { runCatching { it.speak("Warm up.", 1f) } }
                for (w in workers) {
                    status = "Testing $w parallel × $th threads…"
                    val t0 = System.nanoTime()
                    val audio = coroutineScope { engines.take(w).map { e -> async { text.sumOf { e.speak(it, 1f).size.toLong() } * 1000 / e.rate } }.awaitAll().sum() }
                    val xrt = audio / ((System.nanoTime() - t0) / 1_000_000.0)
                    android.util.Log.d("VoxTune", "${v.kind} workers=$w threads=$th audio=${audio}ms wall=${(System.nanoTime() - t0) / 1_000_000}ms x=${"%.1f".format(xrt)}")
                    if (bestCfg == null || xrt > bestCfg.xrt * 1.04) bestCfg = Config(w, th, xrt)
                }
                engines.forEach { it.release() }
            }
            bestCfg?.let { store.setTuneRaw(v.kind, "${it.workers}:${it.threads}:${it.xrt}"); status = null }
        } catch (e: Throwable) { status = "Speed test failed: ${e.message}" } finally { running = false }
    }
}
