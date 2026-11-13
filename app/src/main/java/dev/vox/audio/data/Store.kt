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

    var speed: Float
        get() = p.getFloat("speed", 1f)
        set(v) = p.edit().putFloat("speed", v).apply()

    private fun arr(k: String) = runCatching { JSONArray(p.getString(k, "[]")) }.getOrDefault(JSONArray())
}
