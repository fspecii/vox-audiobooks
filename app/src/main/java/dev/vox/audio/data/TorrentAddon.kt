package dev.vox.audio.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Built-in torrent source. Searches Internet Archive items (each ships an official .torrent) and also accepts a
 * pasted magnet link / 40-char infoHash as the search query. Book.id is the torrent reference itself.
 */
class TorrentAddon : Addon {
    override val id = "torrent"
    override val name = "Torrents"
    override val catalogs = listOf(CatalogDef("archive-torrents", "Torrents · Internet Archive", true))
    private val page = 30
    private val hash = Regex("^[0-9a-fA-F]{40}$")

    override suspend fun catalog(catalogId: String, query: String?, skip: Int): List<Book> {
        val q0 = query?.trim().orEmpty()
        if (q0.startsWith("magnet:") || hash.matches(q0))
            return listOf(Book(q0, id, "Magnet link", "Tap to fetch the file list"))
        var q = "collection:audio_bookspoetry AND mediatype:audio"
        if (q0.isNotBlank()) q += " AND (title:($q0) OR creator:($q0))"
        val url = "https://archive.org/advancedsearch.php?q=${URLEncoder.encode(q, "UTF-8")}" +
            "&fl[]=identifier&fl[]=title&fl[]=creator&rows=$page&page=${skip / page + 1}&output=json&sort[]=downloads+desc"
        val docs = JSONObject(Http.get(url)).getJSONObject("response").getJSONArray("docs")
        return (0 until docs.length()).map {
            val d = docs.getJSONObject(it); val ident = d.getString("identifier")
            val creator = d.opt("creator").let { c -> if (c is JSONArray) c.optString(0) else (c as? String).orEmpty() }
            Book("https://archive.org/download/$ident/${ident}_archive.torrent", id, d.optString("title"), creator,
                "https://archive.org/services/img/$ident")
        }
    }

    override suspend fun meta(book: Book): Book {
        val t = Torrents.resolve(book.id)
        return book.copy(title = if (book.title == "Magnet link") t.name else book.title, chapters = t.chapters)
    }
}
