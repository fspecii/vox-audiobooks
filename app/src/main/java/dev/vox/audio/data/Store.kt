package dev.vox.audio.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Tiny persistence: installed addon URLs, library (saved books) and listening progress. */
class Store(ctx: Context) {
    private val p = ctx.getSharedPreferences("vox", Context.MODE_PRIVATE)

    fun addonUrls(): List<String> = arr("addons").let { a -> (0 until a.length()).map { a.getString(it) } }
    fun setAddonUrls(l: List<String>) = p.edit().putString("addons", JSONArray(l).toString()).apply()

    private fun toBook(o: JSONObject) =
        Book(o.getString("id"), o.getString("addon"), o.getString("title"), o.optString("author"), o.optString("cover").ifBlank { null })
    private fun toJson(it: Book) =
        JSONObject().put("id", it.id).put("addon", it.addonId).put("title", it.title).put("author", it.author).put("cover", it.cover ?: "")

    fun library(): List<Book> = list("library")
    fun setLibrary(l: List<Book>) = p.edit().putString("library", JSONArray(l.map(::toJson)).toString()).apply()

    fun history(): List<Book> = list("history")
    fun setHistory(l: List<Book>) = p.edit().putString("history", JSONArray(l.map(::toJson)).toString()).apply()

    private fun list(k: String) = arr(k).let { a -> (0 until a.length()).map { toBook(a.getJSONObject(it)) } }

    fun progress(key: String): Progress? = p.getString("p:$key", null)?.let {
        val o = JSONObject(it); Progress(key, o.getInt("c"), o.getLong("t"), o.getLong("u"), o.optDouble("f", 0.0).toFloat())
    }
    fun saveProgress(g: Progress) = p.edit().putString("p:${g.bookKey}",
        JSONObject().put("c", g.chapter).put("t", g.positionMs).put("u", g.updatedAt).put("f", g.fraction.toDouble()).toString()).apply()

    // ---- last played book (with chapters) so the app reopens exactly where you stopped
    fun lastBook(): Book? = runCatching {
        val o = JSONObject(p.getString("last", null) ?: return null)
        val ch = o.getJSONArray("ch")
        toBook(o).copy(narrator = null, chapters = (0 until ch.length()).map { i ->
            val c = ch.getJSONObject(i); Chapter(c.getString("t"), c.getString("u"), c.optInt("d"))
        })
    }.getOrNull()
    fun setLastBook(b: Book?) = p.edit().apply {
        if (b == null) remove("last") else putString("last", toJson(b).put("ch", JSONArray(b.chapters.map {
            JSONObject().put("t", it.title).put("u", it.url).put("d", it.durationSec) })).toString())
    }.apply()

    // ---- bookmarks
    fun bookmarks(key: String): List<Bookmark> = runCatching {
        val a = JSONArray(p.getString("bm:$key", "[]"))
        (0 until a.length()).map { val o = a.getJSONObject(it); Bookmark(key, o.getInt("c"), o.getLong("t"), o.optString("n"), o.getLong("u")) }
    }.getOrDefault(emptyList())
    fun setBookmarks(key: String, l: List<Bookmark>) = p.edit().putString("bm:$key", JSONArray(l.map {
        JSONObject().put("c", it.chapter).put("t", it.positionMs).put("n", it.note).put("u", it.createdAt) }).toString()).apply()

    // ---- finished books
    fun finished(): Set<String> = arr("finished").let { a -> (0 until a.length()).map { a.getString(it) }.toSet() }
    fun setFinished(s: Set<String>) = p.edit().putString("finished", JSONArray(s.toList()).toString()).apply()

    // ---- per-book speed + settings
    fun speedFor(key: String): Float? = p.getFloat("sp:$key", 0f).takeIf { it > 0f }
    fun setSpeedFor(key: String, v: Float) = p.edit().putFloat("sp:$key", v).apply()
    var skipSilence: Boolean
        get() = p.getBoolean("skipSilence", false)
        set(v) = p.edit().putBoolean("skipSilence", v).apply()
    var autoUpdate: Boolean
        get() = p.getBoolean("autoUpdate", true)
        set(v) = p.edit().putBoolean("autoUpdate", v).apply()
    var lastUpdateCheck: Long
        get() = p.getLong("updChecked", 0L)
        set(v) = p.edit().putLong("updChecked", v).apply()

    fun tuneRaw(kind: String): String? = p.getString("tune:$kind", null)
    fun setTuneRaw(kind: String, v: String) = p.edit().putString("tune:$kind", v).apply()

    // ---- listening stats (ms per day), onboarding, recent searches
    private fun day(offset: Int = 0) = java.time.LocalDate.now().minusDays(offset.toLong()).toString()
    fun addListened(ms: Long) { val k = "st:${day()}"; p.edit().putLong(k, p.getLong(k, 0L) + ms).apply() }
    fun listenedOn(offset: Int) = p.getLong("st:${day(offset)}", 0L)
    fun streak(): Int { var n = 0; var o = if (listenedOn(0) > 60_000) 0 else 1; while (o < 4000 && listenedOn(o) > 60_000) { n++; o++ }; return n }
    var totalListened: Long
        get() = p.getLong("stTotal", 0L)
        set(v) = p.edit().putLong("stTotal", v).apply()
    var onboarded: Boolean
        get() = p.getBoolean("onboarded", false)
        set(v) = p.edit().putBoolean("onboarded", v).apply()
    fun recentSearches(): List<String> = arr("recent").let { a -> (0 until a.length()).map { a.getString(it) } }
    fun pushSearch(q: String) = p.edit().putString("recent", JSONArray((listOf(q) + recentSearches().filter { !it.equals(q, true) }).take(8)).toString()).apply()
    fun clearSearches() = p.edit().remove("recent").apply()

    // ---- reader
    fun readPos(id: String): Pair<Int, Int>? = p.getString("rd:$id", null)?.split(':')?.let { (it.getOrNull(0)?.toIntOrNull() ?: 0) to (it.getOrNull(1)?.toIntOrNull() ?: 0) }
    fun setReadPos(id: String, chapter: Int, offset: Int) = p.edit().putString("rd:$id", "$chapter:$offset").apply()
    var readerSize: Float
        get() = p.getFloat("rdSize", 19f)
        set(v) = p.edit().putFloat("rdSize", v).apply()
    var readerTheme: Int
        get() = p.getInt("rdTheme", 0)
        set(v) = p.edit().putInt("rdTheme", v).apply()
    var readerSerif: Boolean
        get() = p.getBoolean("rdSerif", true)
        set(v) = p.edit().putBoolean("rdSerif", v).apply()
    var voice: String
        get() = p.getString("voice", "") ?: ""
        set(v) = p.edit().putString("voice", v).apply()

    var speed: Float
        get() = p.getFloat("speed", 1f)
        set(v) = p.edit().putFloat("speed", v).apply()

    private fun arr(k: String) = runCatching { JSONArray(p.getString(k, "[]")) }.getOrDefault(JSONArray())
}
