package dev.vox.audio.reader

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.vox.audio.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest

data class EbookMeta(val id: String, val title: String, val author: String, val lang: String, val cover: String?)
data class GutBook(val id: Int, val title: String, val author: String, val lang: String, val epub: String, val cover: String?)

/** Imported ebooks live in filesDir/ebooks/<id>/ (book.epub|book.txt, meta.json, cover.img). */
object Ebooks {
    private lateinit var app: Context
    var list by mutableStateOf<List<EbookMeta>>(emptyList())
        private set
    private var cached: EBook? = null

    fun init(ctx: Context) {
        if (::app.isInitialized) return
        app = ctx.applicationContext
        refresh()
    }

    private val root get() = File(app.filesDir, "ebooks")
    fun dir(id: String) = File(root, id)
    fun coverFile(id: String) = File(dir(id), "cover.img").takeIf { it.exists() }

    private fun refresh() {
        list = (root.listFiles() ?: emptyArray()).mapNotNull { d ->
            runCatching {
                val o = JSONObject(File(d, "meta.json").readText())
                EbookMeta(d.name, o.getString("title"), o.optString("author"), o.optString("lang", "en"),
                    File(d, "cover.img").takeIf { it.exists() }?.let { "file://${it.path}" })
            }.getOrNull()
        }.sortedByDescending { File(dir(it.id), "meta.json").lastModified() }
    }

    suspend fun importUri(uri: Uri, name: String): String = withContext(Dispatchers.IO) {
        val tmp = File(app.cacheDir, "import-${System.nanoTime()}")
        try {
            app.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            importFile(tmp, name)
        } finally { tmp.delete() }
    }

    suspend fun importFile(tmp: File, name: String): String = withContext(Dispatchers.IO) {
        val md = MessageDigest.getInstance("SHA-1")
        tmp.inputStream().use { i -> val b = ByteArray(65536); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
        val id = md.digest().joinToString("") { "%02x".format(it) }.take(12)
        if (dir(id).exists() && File(dir(id), "meta.json").exists()) return@withContext id
        val isZip = tmp.inputStream().use { it.read() == 'P'.code && it.read() == 'K'.code }
        val file = File(dir(id), if (isZip) "book.epub" else "book.txt").also { it.parentFile!!.mkdirs(); tmp.copyTo(it, true) }
        val parsed = try { if (isZip) Epub.parse(file, id) else Epub.parseText(file, id) } catch (e: Exception) { dir(id).deleteRecursively(); throw e }
        if (parsed.book.chapters.isEmpty()) { dir(id).deleteRecursively(); error("This file has no readable text") }
        val b = parsed.book
        File(dir(id), "meta.json").writeText(JSONObject().put("title", b.title.ifBlank { name }).put("author", b.author).put("lang", b.lang).toString())
        parsed.cover?.let { File(dir(id), "cover.img").writeBytes(it) }
        cached = b
        refresh(); id
    }

    suspend fun load(id: String): EBook = withContext(Dispatchers.IO) {
        cached?.takeIf { it.id == id } ?: run {
            val d = dir(id); val epub = File(d, "book.epub")
            (if (epub.exists()) Epub.parse(epub, id) else Epub.parseText(File(d, "book.txt"), id)).book.also { cached = it }
        }
    }

    fun remove(id: String) { dir(id).deleteRecursively(); File(app.filesDir, "audiobooks/$id").deleteRecursively(); if (cached?.id == id) cached = null; refresh() }

    // ---- Project Gutenberg (public-domain ebooks): the site's own search page is fast, so parse it
    suspend fun gutenberg(q: String): List<GutBook> {
        val html = Http.get("https://www.gutenberg.org/ebooks/search/?query=${URLEncoder.encode(q, "UTF-8")}&submit_search=Go%21")
        return org.jsoup.Jsoup.parse(html).select("li.booklink").mapNotNull { li ->
            val id = li.selectFirst("a.link")?.attr("href")?.substringAfterLast('/')?.toIntOrNull() ?: return@mapNotNull null
            GutBook(id, li.selectFirst("span.title")?.text().orEmpty(), li.selectFirst("span.subtitle")?.text().orEmpty(), "en",
                "https://www.gutenberg.org/ebooks/$id.epub3.images", li.selectFirst("img.cover-thumb")?.attr("src")?.let { "https://www.gutenberg.org$it" })
        }.take(30)
    }

    suspend fun downloadGutenberg(g: GutBook): String = withContext(Dispatchers.IO) {
        val tmp = File(app.cacheDir, "gut-${g.id}.epub")
        try {
            Http.client.newCall(Request.Builder().url(g.epub).header("User-Agent", "Vox").build()).execute().use { r ->
                if (!r.isSuccessful) error("HTTP ${r.code}")
                r.body!!.byteStream().use { i -> tmp.outputStream().use { i.copyTo(it) } }
            }
            importFile(tmp, g.title)
        } finally { tmp.delete() }
    }
}
