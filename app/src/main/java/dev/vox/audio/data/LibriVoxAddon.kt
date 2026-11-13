package dev.vox.audio.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Built-in source: LibriVox public-domain audiobooks (official API). */
class LibriVoxAddon : Addon {
    override val id = "librivox"
    override val name = "LibriVox"
    override val catalogs = listOf(CatalogDef("latest", "LibriVox · Newest", true))
    private val page = 30

    override suspend fun catalog(catalogId: String, query: String?, skip: Int): List<Book> {
        val base = "https://librivox.org/api/feed/audiobooks?format=json&coverart=1&limit=$page&offset=$skip"
        if (query.isNullOrBlank()) return parse(base)
        val q = URLEncoder.encode("^" + query.trim(), "UTF-8")
        val byTitle = runCatching { parse("$base&title=$q") }.getOrDefault(emptyList())
        if (byTitle.isNotEmpty()) return byTitle
        return runCatching { parse("$base&author=$q") }.getOrDefault(emptyList())
    }

    override suspend fun meta(book: Book): Book {
        val url = "https://librivox.org/api/feed/audiobooks?format=json&extended=1&coverart=1&id=${book.id}"
        return parse(url, extended = true).firstOrNull() ?: book
    }

    private suspend fun parse(url: String, extended: Boolean = false): List<Book> {
        val root = JSONObject(Http.get(url))
        val arr = root.optJSONArray("books") ?: return emptyList()
        return (0 until arr.length()).map { toBook(arr.getJSONObject(it), extended) }
    }

    private fun toBook(o: JSONObject, extended: Boolean): Book {
        val authors = o.optJSONArray("authors") ?: JSONArray()
        val author = (0 until authors.length()).joinToString(", ") {
            val a = authors.getJSONObject(it)
            "${a.optString("first_name")} ${a.optString("last_name")}".trim()
        }
        val secs = o.optJSONArray("sections") ?: JSONArray()
        val chapters = (0 until secs.length()).map {
            val s = secs.getJSONObject(it)
            Chapter(s.optString("title").trim().ifBlank { "Chapter ${it + 1}" }, s.optString("listen_url"), s.optInt("playtime"))
        }.filter { it.url.isNotBlank() }
        return Book(
            id = o.getString("id"), addonId = id,
            title = o.optString("title").stripHtml(),
            author = author,
            cover = o.optString("coverart_jpg").ifBlank { null },
            description = o.optString("description").stripHtml(),
            totalSec = o.optInt("totaltimesecs"),
            chapters = if (extended) chapters else emptyList(),
        )
    }
}
