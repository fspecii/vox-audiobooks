package dev.vox.audio.reader

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import kotlinx.coroutines.*
import java.io.File

/** Developer benchmark: `adb shell am start -n dev.vox.audio/.MainActivity --ez bench true`. Results go to logcat (tag VoxBench). */
object Bench {
    private val en = "It was a bright cold day in April, and the clocks were striking thirteen. Winston Smith, his chin nuzzled into his breast in an effort to escape the vile wind, slipped quickly through the glass doors of Victory Mansions, though not quickly enough to prevent a swirl of gritty dust from entering along with him. The hallway smelt of boiled cabbage and old rag mats."
    private val ro = "Era o zi rece și senină de aprilie, iar ceasurile băteau ora treisprezece. Winston Smith se strecură repede prin ușile de sticlă ale clădirii, cu bărbia ascunsă în piept, ca să scape de vântul rău, dar nu destul de repede ca să nu intre odată cu el un vârtej de praf. Holul mirosea a varză fiartă și a covoare vechi."

    private fun piper(dir: File, threads: Int): OfflineTts {
        val onnx = dir.listFiles { f -> f.name.endsWith(".onnx") }!!.first()
        val vits = OfflineTtsVitsModelConfig(model = onnx.path, tokens = File(dir, "tokens.txt").path, dataDir = File(dir, "espeak-ng-data").path)
        return OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(vits = vits, numThreads = threads, debug = false, provider = "cpu")))
    }

    private fun supertonic(dir: File, threads: Int): OfflineTts {
        fun f(n: String) = File(dir, n).path
        val st = OfflineTtsSupertonicModelConfig(f("duration_predictor.int8.onnx"), f("text_encoder.int8.onnx"), f("vector_estimator.int8.onnx"),
            f("vocoder.int8.onnx"), f("tts.json"), f("unicode_indexer.bin"), f("voice.bin"))
        return OfflineTts(config = OfflineTtsConfig(model = OfflineTtsModelConfig(supertonic = st, numThreads = threads, debug = false, provider = "cpu")))
    }

    private fun gen(t: OfflineTts, text: String, lang: String?, steps: Int = 0): Pair<Int, Long> {
        val t0 = System.nanoTime()
        val a = if (lang != null) t.generateWithConfig(text, GenerationConfig(speed = 1f, sid = 0, numSteps = steps, extra = mapOf("lang" to lang))) else t.generate(text, 0, 1f)
        return (a.samples.size * 1000L / a.sampleRate).toInt() to (System.nanoTime() - t0) / 1_000_000
    }

    fun run(ctx: Context) = Thread {
        fun log(m: String) = Log.d("VoxBench", m)
        val voices = File(ctx.filesDir, "voices")
        log("cores=${Runtime.getRuntime().availableProcessors()}")
        runCatching {
            val p = File(voices, "en_US-lessac-medium")
            for (th in listOf(1, 2, 4, 8)) {
                val t = piper(p, th); gen(t, "Warm up.", null)
                val (ms, took) = gen(t, en, null); log("piper-lessac threads=$th audio=${ms}ms took=${took}ms rtf=${"%.3f".format(took / ms.toDouble())}")
                t.release()
            }
            // parallel instances, 2 threads each
            for (n in listOf(2, 3, 4)) {
                val ts = List(n) { piper(p, 2) }; ts.forEach { gen(it, "Warm up.", null) }
                val t0 = System.nanoTime(); var audio = 0
                runBlocking { ts.map { t -> async(Dispatchers.Default) { gen(t, en, null).first } }.awaitAll().forEach { audio += it } }
                val took = (System.nanoTime() - t0) / 1_000_000
                log("piper-lessac parallel x$n(2thr) audio=${audio}ms took=${took}ms rtf=${"%.3f".format(took / audio.toDouble())}")
                ts.forEach { it.release() }
            }
        }.onFailure { log("piper bench failed: $it") }
        runCatching {
            val s = File(voices, "supertonic3")
            for (th in listOf(2, 4, 8)) for (steps in listOf(3, 5)) {
                val t = supertonic(s, th); gen(t, "Warm up.", "en", steps)
                val (ms, took) = gen(t, en, "en", steps); log("supertonic en threads=$th steps=$steps audio=${ms}ms took=${took}ms rtf=${"%.3f".format(took / ms.toDouble())}")
                if (th == 4 && steps == 5) { val (m2, t2) = gen(t, ro, "ro", steps); log("supertonic ro threads=$th steps=$steps audio=${m2}ms took=${t2}ms rtf=${"%.3f".format(t2 / m2.toDouble())}") }
                t.release()
            }
        }.onFailure { log("supertonic bench failed: $it") }
        log("done")
    }.start()
}
