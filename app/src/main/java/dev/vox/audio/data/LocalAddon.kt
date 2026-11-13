package dev.vox.audio.data

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Audiobooks generated on this phone from ebooks (see reader/Converter). */
class LocalAddon(private val ctx: Context) : Addon {
    override val id = "local"
    override val name = "My audiobooks"
    override val catalogs = listOf(CatalogDef("mine", "Made on this phone", false))
    private val root get() = File(ctx.filesDir, "audiobooks")

    private fun read(f: File): Book? = runCatching {
        val o = JSONObject(f.readText()); if (o.optInt("v") < 3) return@runCatching null; val ch = o.getJSONArray("chapters")
        Book(o.getString("id"), id, o.getString("title"), o.optString("author"), o.optString("cover").ifBlank { null },
            chapters = (0 until ch.length()).map { val c = ch.getJSONObject(it)
                Chapter(c.getString("title"), "file://" + c.getString("file"), c.optInt("sec"), c.optInt("pid", it), c.optInt("ch", it)) },
            totalSec = (0 until ch.length()).sumOf { ch.getJSONObject(it).optInt("sec") },
            pending = (o.optInt("total", ch.length()) - ch.length()).coerceAtLeast(0))
    }.getOrNull()

    override suspend fun catalog(catalogId: String, query: String?, skip: Int): List<Book> =
        if (skip > 0) emptyList() else (root.listFiles() ?: emptyArray()).mapNotNull { read(File(it, "book.json")) }.filter { it.chapters.isNotEmpty() }
    override suspend fun meta(book: Book): Book = read(File(root, "${book.id}/book.json")) ?: book
}
