package dev.vox.audio.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** GET with up to 3 tries: upstream 5xx (LibriVox likes to answer 522) and network blips are retried with a short back-off. */
    suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        var last: Exception? = null
        for (attempt in 0 until 3) {
            try {
                val req = Request.Builder().url(url).header("User-Agent", "Vox").build()
                client.newCall(req).execute().use { r ->
                    if (r.code in 500..599) { last = IllegalStateException("HTTP ${r.code} for $url"); return@use }
                    if (!r.isSuccessful) error("HTTP ${r.code} for $url")
                    return@withContext r.body?.string() ?: ""
                }
            } catch (e: java.io.IOException) { last = e }
            kotlinx.coroutines.delay(600L * (attempt + 1))
        }
        throw last ?: IllegalStateException("Request failed")
    }

    suspend fun getBytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("User-Agent", "Vox").build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code} for $url")
            r.body?.bytes() ?: ByteArray(0)
        }
    }
}

fun String.stripHtml(): String =
    replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
        .replace("&nbsp;", " ").trim()
