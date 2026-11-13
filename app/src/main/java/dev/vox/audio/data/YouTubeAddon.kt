package dev.vox.audio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * YouTube via yt-dlp. Search results are books; a video is a one-chapter book, a playlist is a multi-chapter book.
 * Chapter URLs are `yt://VIDEOID` and are resolved to real stream URLs lazily by the player (see PlaybackService).
 */
class YouTubeAddon : Addon {
    override val id = "youtube"
    override val name = "YouTube"
    override val catalogs = listOf(CatalogDef("yt-audiobooks", "YouTube · Audiobooks", true))
    private val page = 24

    private fun isYtUrl(s: String) = Regex("^(https?://)?(www\\.|m\\.|music\\.)?(youtube\\.com|youtu\\.be)/.+").matches(s.trim())
    private fun cover(id: String) = "https://i.ytimg.com/vi/$id/hqdefault.jpg"

    override suspend fun catalog(catalogId: String, query: String?, skip: Int): List<Book> = withContext(Dispatchers.IO) {
        val q = query?.trim().orEmpty()
        if (isYtUrl(q)) {
            val url = if (q.startsWith("http")) q else "https://$q"
            val j = YT.json(url, "--playlist-items", "1")
            val title = j.optString("title", "YouTube")
            return@withContext listOf(Book(url, id, title, j.optString("uploader", j.optString("channel")),
                if (j.has("entries")) j.optJSONArray("entries")?.optJSONObject(0)?.optString("id")?.let { cover(it) } else cover(j.optString("id"))))
        }
        val term = if (q.isBlank()) "full audiobook" else if (q.contains("audiobook", true)) q else "$q audiobook"
        val n = skip + page
        val entries = YT.json("ytsearch$n:$term").optJSONArray("entries") ?: return@withContext emptyList()
        (skip until entries.length()).mapNotNull { i ->
            val e = entries.optJSONObject(i) ?: return@mapNotNull null
            val vid = e.optString("id").ifBlank { return@mapNotNull null }
            Book("https://www.youtube.com/watch?v=$vid", id, e.optString("title"),
                e.optString("uploader", e.optString("channel")), cover(vid), totalSec = e.optInt("duration"))
        }
    }

    override suspend fun meta(book: Book): Book = withContext(Dispatchers.IO) {
        val j = YT.json(book.id)
        val entries = j.optJSONArray("entries")
        if (entries != null) {
            val ch = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.filter { it.optString("id").isNotBlank() }
                .map { Chapter(it.optString("title"), "yt://" + it.getString("id"), it.optInt("duration")) }
            book.copy(title = j.optString("title", book.title), author = j.optString("uploader", book.author),
                cover = book.cover ?: ch.firstOrNull()?.let { cover(it.url.removePrefix("yt://")) },
                totalSec = ch.sumOf { it.durationSec }, chapters = ch)
        } else {
            val vid = j.getString("id")
            book.copy(title = j.optString("title", book.title), author = j.optString("uploader", book.author),
                description = j.optString("description"), totalSec = j.optInt("duration"),
                chapters = listOf(Chapter(j.optString("title", book.title), "yt://$vid", j.optInt("duration"))))
        }
    }
}
