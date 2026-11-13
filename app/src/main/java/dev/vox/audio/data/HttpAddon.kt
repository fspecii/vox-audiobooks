package dev.vox.audio.data

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Remote addon speaking a Stremio-style protocol (installed from a manifest URL):
 *   GET {base}/manifest.json
 *   GET {base}/catalog/audiobook/{catalogId}[/search={q}][/skip={n}].json  -> {"metas":[...]}
 *   GET {base}/meta/audiobook/{id}.json                                    -> {"meta":{..., "chapters":[...]}}
 */
class HttpAddon(val manifestUrl: String, private val manifest: JSONObject) : Addon {
    private val base = manifestUrl.removeSuffix("/manifest.json").trimEnd('/')
    override val id = "http:" + manifest.getString("id")
    override val name = manifest.optString("name", manifest.getString("id"))
    override val catalogs: List<CatalogDef> = manifest.optJSONArray("catalogs")?.let { arr ->
        (0 until arr.length()).map { arr.getJSONObject(it) }.filter { it.optString("type", "audiobook") == "audiobook" }.map { c ->
            val extra = c.optJSONArray("extra")
            val searchable = extra != null && (0 until extra.length()).any { extra.getJSONObject(it).optString("name") == "search" }
            CatalogDef(c.getString("id"), c.optString("name", c.getString("id")), searchable)
        }
    } ?: emptyList()

    override suspend fun catalog(catalogId: String, query: String?, skip: Int): List<Book> {
        var path = "$base/catalog/audiobook/$catalogId"
        if (!query.isNullOrBlank()) path += "/search=" + URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        if (skip > 0) path += "/skip=$skip"
        val metas = JSONObject(Http.get("$path.json")).optJSONArray("metas") ?: return emptyList()
        return (0 until metas.length()).map { toBook(metas.getJSONObject(it)) }
    }

    override suspend fun meta(book: Book): Book {
        val id = URLEncoder.encode(book.id, "UTF-8").replace("+", "%20")
        val m = JSONObject(Http.get("$base/meta/audiobook/$id.json")).getJSONObject("meta")
        val b = toBook(m)
        // Torrent-backed items: {"magnet": "..."} or {"infoHash": "...", "trackers": [...]}
        val ref = m.optString("magnet").ifBlank {
            m.optString("infoHash").takeIf { it.isNotBlank() }?.let { h ->
                "magnet:?xt=urn:btih:$h" + (m.optJSONArray("trackers")?.let { t ->
                    (0 until t.length()).joinToString("") { "&tr=" + URLEncoder.encode(t.getString(it), "UTF-8") } } ?: "")
            }.orEmpty()
        }
        if (b.chapters.isEmpty() && ref.isNotBlank()) return b.copy(chapters = Torrents.resolve(ref).chapters)
        return b
    }

    private fun toBook(o: JSONObject): Book {
        val ch = o.optJSONArray("chapters")
        return Book(
            id = o.getString("id"), addonId = this.id, title = o.optString("name"),
            author = o.optString("author"), cover = o.optString("poster").ifBlank { null },
            description = o.optString("description"), narrator = o.optString("narrator").ifBlank { null },
            totalSec = o.optInt("duration"),
            chapters = ch?.let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.filter { it.optString("url").isNotBlank() }
                    .map { Chapter(it.optString("title"), it.getString("url"), it.optInt("duration")) }
            } ?: emptyList(),
        )
    }

    companion object {
        suspend fun install(url: String): HttpAddon {
            val u = url.trim().replaceFirst(Regex("^stremio://"), "https://").replaceFirst(Regex("^vox://"), "https://")
            val mu = if (u.endsWith("manifest.json")) u else u.trimEnd('/') + "/manifest.json"
            return HttpAddon(mu, JSONObject(Http.get(mu)))
        }
    }
}
