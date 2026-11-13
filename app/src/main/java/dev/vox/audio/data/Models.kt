package dev.vox.audio.data

data class Chapter(val title: String, val url: String, val durationSec: Int = 0,
                   val id: Int = -1,     // on-device audiobooks: stable part id
                   val ch: Int = -1)     // on-device audiobooks: chapter of the ebook this part belongs to

data class Book(
    val id: String,          // unique inside its addon
    val addonId: String,
    val title: String,
    val author: String = "",
    val cover: String? = null,
    val description: String = "",
    val narrator: String? = null,
    val totalSec: Int = 0,
    val chapters: List<Chapter> = emptyList(),
    val pending: Int = 0,        // chapters still being generated (on-device audiobooks)
) {
    val key get() = "$addonId|$id"
}

data class CatalogDef(val id: String, val name: String, val searchable: Boolean)

/** Same idea as a Stremio addon: a manifest, catalogs of books, and a meta call that returns chapters + streams. */
interface Addon {
    val id: String
    val name: String
    val catalogs: List<CatalogDef>
    suspend fun catalog(catalogId: String, query: String?, skip: Int): List<Book>
    suspend fun meta(book: Book): Book
}

data class Progress(val bookKey: String, val chapter: Int, val positionMs: Long, val updatedAt: Long, val fraction: Float = 0f)

data class Bookmark(val bookKey: String, val chapter: Int, val positionMs: Long, val note: String, val createdAt: Long)
