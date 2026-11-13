package dev.vox.audio.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Built-in source: Internet Archive "audio_bookspoetry" collection (public/free uploads). */
class ArchiveAddon : Addon {
    override val id = "archive"
    override val name = "Internet Archive"
    override val catalogs = listOf(CatalogDef("popular", "Internet Archive · Popular", true))
    private val page = 30

    override suspend fun catalog(catalogId: String, query: String?, skip: Int): List<Book> {
        var q = "collection:audio_bookspoetry AND mediatype:audio"
        if (!query.isNullOrBlank()) q += " AND (title:(${query.trim()}) OR creator:(${query.trim()}))"
        val url = "https://archive.org/advancedsearch.php?q=${URLEncoder.encode(q, "UTF-8")}" +
            "&fl[]=identifier&fl[]=title&fl[]=creator&rows=$page&page=${skip / page + 1}&output=json&sort[]=downloads+desc"
        val docs = JSONObject(Http.get(url)).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).map {
            val d = docs.getJSONObject(it)
            Book(d.getString("identifier"), id, d.optString("title"), firstString(d.opt("creator")),
                "https://archive.org/services/img/${d.getString("identifier")}")
        }
    }

    override suspend fun meta(book: Book): Book {
        val m = JSONObject(Http.get("https://archive.org/metadata/${book.id}"))
        val files = m.optJSONArray("files") ?: JSONArray()
        val all = (0 until files.length()).map { files.getJSONObject(it) }.filter { it.optString("name").endsWith(".mp3", true) }
        // Archive stores several derivatives per track; keep one quality tier.
        val pick = listOf("VBR MP3", "64Kbps MP3", "128Kbps MP3").firstNotNullOfOrNull { fmt ->
            all.filter { it.optString("format") == fmt }.takeIf { it.isNotEmpty() }
        } ?: all
        val tracks = pick.sortedBy { it.optString("name") }
        val desc = m.optJSONObject("metadata")?.opt("description")?.let { firstString(it) }.orEmpty().stripHtml()
        return book.copy(
            description = desc,
            chapters = tracks.mapIndexed { i, f ->
                Chapter(f.optString("title").ifBlank { "Track ${i + 1}" },
                    "https://archive.org/download/${book.id}/" + URLEncoder.encode(f.getString("name"), "UTF-8").replace("+", "%20"),
                    f.optString("length").toDoubleOrNull()?.toInt() ?: 0)
            },
        )
    }

    private fun firstString(v: Any?): String = when (v) {
        is JSONArray -> if (v.length() > 0) v.optString(0) else ""
        is String -> v
        else -> ""
    }
}
