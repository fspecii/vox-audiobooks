package dev.vox.audio.reader

/** A part is a few minutes of speech: a run of whole sentences inside one chapter. Parts are the unit of generation and playback. */
data class Part(val id: Int, val chapter: Int, val start: Int, val end: Int, val title: String)

object Plan {
    const val PART_CHARS = 3200          // about 3.5 minutes of speech

    fun chapterText(ch: EChapter) = ch.title + "\n \n" + ch.paras.joinToString("\n") + "\n"

    private var cache: Pair<String, List<Part>>? = null

    /** Deterministic split of a book into parts, identical every time so ids stay valid across runs. */
    @Synchronized fun parts(book: EBook): List<Part> {
        cache?.takeIf { it.first == book.id }?.let { return it.second }
        val out = ArrayList<Part>()
        book.chapters.forEachIndexed { ci, ch ->
            val text = chapterText(ch)
            val sents = Sentences.split(text)
            val ranges = ArrayList<IntRange>()
            var from = 0; var startedAt = 0
            for ((i, s) in sents.withIndex()) {
                if (s.end - startedAt >= PART_CHARS || i == sents.lastIndex) {
                    ranges.add(startedAt until s.end)
                    startedAt = if (i < sents.lastIndex) sents[i + 1].start else s.end
                }
            }
            if (ranges.isEmpty()) ranges.add(0 until text.length)
            ranges.forEachIndexed { k, r ->
                out.add(Part(out.size, ci, if (k == 0) 0 else r.first, r.last + 1,
                    if (ranges.size > 1) "${ch.title} · ${k + 1}/${ranges.size}" else ch.title))
            }
        }
        cache = book.id to out
        return out
    }

    fun partFor(parts: List<Part>, chapter: Int, offset: Int): Part? =
        parts.lastOrNull { it.chapter == chapter && it.start <= offset } ?: parts.firstOrNull { it.chapter == chapter }
}
