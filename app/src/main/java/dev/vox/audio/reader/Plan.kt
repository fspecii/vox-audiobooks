package dev.vox.audio.reader

/** A part is a few minutes of speech: a run of whole sentences inside one chapter. Parts are the unit of generation and playback. */
data class Part(val id: Int, val chapter: Int, val start: Int, val end: Int, val title: String)

object Plan {
    const val PART_CHARS = 3200          // about 3.5 minutes of speech

    fun chapterText(ch: EChapter) = ch.title + "\n \n" + ch.paras.joinToString("\n") + "\n"

    private val meta = Regex("^\\s*(Title|Author|Editor|Translator|Illustrator|Release date|Most recently updated|Language|Credits|Produced by|Character set encoding|Other information and formats|Updated|Posting Date|Copyright|\\[eBook|Contents|Table of Contents|Illustrations?|List of Illustrations|CONTENTS)\\b[^\\n]*", RegexOption.IGNORE_CASE)
    private val urlOnly = Regex("(https?://|www\\.)\\S+")
    private val boiler = Regex("project gutenberg|gutenberg-tm|full license|transcriber|end of (the )?project|start of (the )?project", RegexOption.IGNORE_CASE)

    /** Front/back matter that nobody wants read aloud: licence, catalogue data, table of contents, URLs. */
    fun speakable(text: String, s: Sent, chapterIndex: Int, chapterCount: Int, chapterTitle: String): Boolean {
        val t = text.substring(s.start, s.end).trim()
        if (t.length < 2 || t.none { it.isLetterOrDigit() }) return false
        if (urlOnly.containsMatchIn(t) && t.length < 140) return false
        if (chapterIndex < 3 && (meta.matches(t) || boiler.containsMatchIn(t))) return false
        if ((chapterIndex >= chapterCount - 2 && chapterCount > 3) && boiler.containsMatchIn(t)) return false
        if (t.equals("contents", true) || t.equals("table of contents", true)) return false
        return true
    }

    /** What the voice should actually say for a sentence: no markup, no footnote marks, no URLs, calm dashes. */
    fun speakText(raw: String): String {
        var t = raw.replace('\n', ' ')
        t = t.replace(Regex("\\[(Illustration|Footnote|Sidenote)[^\\]]*\\]", RegexOption.IGNORE_CASE), " ")
        t = t.replace(Regex("\\[\\d+\\]"), " ").replace(urlOnly, " ")
        t = t.replace(Regex("(?<![\\p{L}\\p{N}])_+|_+(?![\\p{L}\\p{N}])"), "").replace('*', ' ')
        t = t.replace("—", ", ").replace("–", ", ").replace("…", "...")
        t = t.replace(Regex("\\s+"), " ").trim()
        if (t.isNotEmpty() && t.last().isLetterOrDigit()) t += "."
        return t
    }

    private val sentCache = HashMap<String, List<Sent>>()
    /** The sentences that are spoken, in order, for chapter [ci]. Positions refer to [chapterText]. */
    @Synchronized fun sentences(book: EBook, ci: Int): List<Sent> = sentCache.getOrPut("${book.id}/$ci") {
        val ch = book.chapters[ci]; val text = chapterText(ch)
        Sentences.split(text).filter { speakable(text, it, ci, book.chapters.size, ch.title) }
    }

    /** True when the sentence is the last one of its paragraph (or a heading): the voice rests longer there. */
    fun paragraphEnd(text: String, s: Sent) = text.substring(s.end, minOf(text.length, s.end + 4)).contains('\n')

    private var cache: Pair<String, List<Part>>? = null

    /** Deterministic split of a book into parts, identical every time so ids stay valid across runs. */
    @Synchronized fun parts(book: EBook): List<Part> {
        cache?.takeIf { it.first == book.id }?.let { return it.second }
        val out = ArrayList<Part>()
        book.chapters.forEachIndexed { ci, ch ->
            val sents = sentences(book, ci)
            if (sents.isEmpty()) return@forEachIndexed
            val ranges = ArrayList<IntRange>()
            var startedAt = sents.first().start
            for ((i, s) in sents.withIndex()) {
                if (s.end - startedAt >= PART_CHARS || i == sents.lastIndex) {
                    ranges.add(startedAt until s.end)
                    startedAt = if (i < sents.lastIndex) sents[i + 1].start else s.end
                }
            }
            ranges.forEachIndexed { k, r ->
                out.add(Part(out.size, ci, if (k == 0) 0 else r.first, r.last + 1,
                    if (ranges.size > 1) "${ch.title} · ${k + 1}/${ranges.size}" else ch.title))
            }
        }
        cache = book.id to out
        return out
    }

    fun partFor(parts: List<Part>, chapter: Int, offset: Int): Part? =
        parts.lastOrNull { it.chapter == chapter && it.start <= offset }
            ?: parts.firstOrNull { it.chapter == chapter }
            ?: parts.firstOrNull { it.chapter > chapter }          // chapter is not read aloud (front matter): start at the next one
            ?: parts.lastOrNull()
}
