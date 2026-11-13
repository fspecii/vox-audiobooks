package dev.vox.audio.reader

import android.content.Context
import java.io.File

/**
 * Sentence timeline of a converted chapter: "startMs charStart charEnd" per line. It ties the audiobook position to the
 * exact sentence on the page (and back), so the book and the player can follow each other.
 */
object SyncMap {
    private val cache = HashMap<String, List<LongArray>>()

    fun file(ctx: Context, id: String, ci: Int) = File(ctx.filesDir, "audiobooks/$id/%03d.map".format(ci + 1))

    fun write(f: File, entries: List<LongArray>) = f.writeText(entries.joinToString("\n") { "${it[0]} ${it[1]} ${it[2]}" })

    private fun load(ctx: Context, id: String, ci: Int): List<LongArray>? {
        val key = "$id/$ci"
        cache[key]?.let { return it }
        val f = file(ctx, id, ci).takeIf { it.exists() } ?: return null
        val m = runCatching { f.readLines().filter { it.isNotBlank() }.map { l -> l.split(' ').map { it.toLong() }.toLongArray() } }.getOrNull()
        if (m != null && m.isNotEmpty()) cache[key] = m
        return m
    }

    /** Characters (in the chapter text) being spoken at [ms]. Without a map: proportional guess when lengths are known. */
    fun charAt(ctx: Context, id: String, ci: Int, ms: Long, plainLen: Int = 0, durMs: Long = 0): IntRange? {
        val m = load(ctx, id, ci)
        if (m != null) {
            var lo = 0; var hi = m.size - 1
            while (lo < hi) { val mid = (lo + hi + 1) / 2; if (m[mid][0] <= ms) lo = mid else hi = mid - 1 }
            return m[lo][1].toInt() until m[lo][2].toInt()
        }
        if (plainLen > 0 && durMs > 0) { val o = (ms.coerceIn(0, durMs) * plainLen / durMs).toInt().coerceIn(0, plainLen - 1); return o..o }
        return null
    }

    /** Audio time of the sentence that contains character [offset]. */
    fun msAt(ctx: Context, id: String, ci: Int, offset: Int, plainLen: Int = 0, durMs: Long = 0): Long {
        val m = load(ctx, id, ci)
        if (m != null) return (m.firstOrNull { it[2] > offset } ?: m.last())[0]
        return if (plainLen > 0 && durMs > 0) offset.toLong().coerceIn(0, plainLen.toLong()) * durMs / plainLen else 0L
    }
}
