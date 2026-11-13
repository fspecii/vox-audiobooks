package dev.vox.audio.reader

import org.jsoup.Jsoup
import org.jsoup.parser.Parser
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

data class EChapter(val title: String, val paras: List<String>)
data class EBook(val id: String, val title: String, val author: String, val lang: String, val chapters: List<EChapter>)

/** Minimal EPUB reader: container.xml -> OPF -> spine -> plain-text paragraphs per chapter. */
object Epub {
    private const val BLOCKS = "p,li,h1,h2,h3,h4,h5,h6,blockquote,pre"

    class Parsed(val book: EBook, val cover: ByteArray?)

    private fun norm(base: String, href: String): String {
        val parts = ArrayList<String>()
        val full = (if (base.isEmpty()) "" else "$base/") + URLDecoder.decode(href.substringBefore('#'), "UTF-8")
        for (seg in full.split('/')) when (seg) { "", "." -> {} ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex) else -> parts.add(seg) }
        return parts.joinToString("/")
    }

    fun parse(file: File, id: String): Parsed = ZipFile(file).use { z ->
        fun read(path: String): ByteArray? = (z.getEntry(path) ?: z.entries().asSequence().firstOrNull { it.name.equals(path, true) })
            ?.let { z.getInputStream(it).readBytes() }
        val container = Jsoup.parse(String(read("META-INF/container.xml") ?: error("Not an EPUB")), "", Parser.xmlParser())
        val opfPath = container.selectFirst("rootfile")?.attr("full-path") ?: error("No OPF")
        val base = opfPath.substringBeforeLast('/', "")
        val opf = Jsoup.parse(String(read(opfPath) ?: error("No OPF")), "", Parser.xmlParser())
        fun meta(tag: String) = opf.getElementsByTag(tag).firstOrNull()?.text()?.trim().orEmpty()

        val items = opf.select("manifest > item").associate { it.attr("id") to it }
        var cover: ByteArray? = null
        val coverItem = opf.select("meta[name=cover]").firstOrNull()?.attr("content")?.let { items[it] }
            ?: items.values.firstOrNull { it.attr("properties").contains("cover-image") }
        if (coverItem != null) cover = read(norm(base, coverItem.attr("href")))

        val chapters = ArrayList<EChapter>()
        for (ref in opf.select("spine > itemref")) {
            val item = items[ref.attr("idref")] ?: continue
            val type = item.attr("media-type")
            if (!type.contains("html") && !type.contains("xml")) continue
            val html = read(norm(base, item.attr("href"))) ?: continue
            val doc = Jsoup.parse(String(html), "", Parser.htmlParser())
            doc.select("script,style,nav,sup.noteref").remove()
            val body = doc.body()
            var blocks = body.select(BLOCKS).filter { it.select(BLOCKS).size == 1 }
            val paras = if (blocks.isNotEmpty()) blocks.map { clean(it.text()) } else body.wholeText().split(Regex("\n\\s*\n")).map { clean(it) }
            val text = paras.filter { it.isNotBlank() }
            if (text.isEmpty()) continue
            val heading = body.selectFirst("h1,h2,h3")?.text()?.let { clean(it) }?.takeIf { it.isNotBlank() }
            if (heading == null && chapters.isNotEmpty()) {          // continuation of the previous chapter file
                val last = chapters.removeAt(chapters.lastIndex); chapters.add(last.copy(paras = last.paras + text))
            } else {
                val body2 = if (heading != null && text.first() == heading) text.drop(1) else text
                chapters.add(EChapter(heading ?: doc.title().ifBlank { "Section ${chapters.size + 1}" }, body2))
            }
        }
        val title = meta("dc:title").ifBlank { file.nameWithoutExtension }
        Parsed(EBook(id, title, meta("dc:creator"), meta("dc:language").substringBefore('-').lowercase().ifBlank { "en" },
            chapters.filter { it.paras.isNotEmpty() }), cover)
    }

    /** Plain .txt: blank-line paragraphs, chapters of ~60 paragraphs. */
    fun parseText(file: File, id: String): Parsed {
        val paras = file.readText().split(Regex("\n\\s*\n")).map { clean(it) }.filter { it.isNotBlank() }
        val chunks = paras.chunked(60).mapIndexed { i, c -> EChapter("Part ${i + 1}", c) }
        return Parsed(EBook(id, file.nameWithoutExtension, "", "en", chunks), null)
    }

    private fun clean(s: String) = s.replace(' ', ' ').replace(Regex("\\s+"), " ").trim()
}
