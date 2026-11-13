package dev.vox.audio

import dev.vox.audio.data.Updater
import dev.vox.audio.reader.*
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    @Test fun versionCompare() {
        assertTrue(Updater.isNewer("0.4.1", "0.4.0"))
        assertTrue(Updater.isNewer("v1.0.0", "0.9.9"))
        assertTrue(Updater.isNewer("0.10.0", "0.9.0"))
        assertFalse(Updater.isNewer("0.4.0", "0.4.0"))
        assertFalse(Updater.isNewer("0.3.9", "0.4.0"))
    }

    @Test fun sentencesSplitAndCover() {
        val t = "Mr. Smith went home. He said \"Hello!\" Then he left.\nNew line here. Dr. Who? Yes."
        val s = Sentences.split(t)
        assertTrue(s.size >= 4)
        s.forEach { assertTrue(it.start < it.end); assertTrue(it.end <= t.length) }
        for (i in 0 until s.size - 1) assertTrue("sorted", s[i].end <= s[i + 1].start)
        assertEquals("Mr. Smith went home.", t.substring(s[0].start, s[0].end))   // abbreviation does not split
    }

    @Test fun longSentenceIsChunked() {
        val t = (1..60).joinToString(", ") { "word$it" } + "."
        val s = Sentences.split(t)
        assertTrue(s.size > 1)
        s.forEach { assertTrue(it.end - it.start <= 300) }
    }

    @Test fun partsCoverEveryChapterWithoutOverlap() {
        val paras = (1..400).map { "This is paragraph number $it. It has a second sentence to be split. And a third one." }
        val book = EBook("t-plan", "T", "A", "en", listOf(EChapter("One", paras), EChapter("Two", paras.take(30)), EChapter("Three", listOf("Short."))))
        val parts = Plan.parts(book)
        assertEquals(parts.indices.toList(), parts.map { it.id })
        for (ci in 0..2) {
            val mine = parts.filter { it.chapter == ci }
            assertTrue(mine.isNotEmpty())
            assertEquals(0, mine.first().start)
            val text = Plan.chapterText(book.chapters[ci])
            assertTrue(mine.last().end >= text.trimEnd().length)
            for (i in 0 until mine.size - 1) assertTrue(mine[i].end <= mine[i + 1].start + 1)
        }
        assertTrue("long chapter is split", parts.count { it.chapter == 0 } > 3)
        assertEquals(parts, Plan.parts(book))   // deterministic
    }

    @Test fun frontMatterIsNotRead() {
        val paras = listOf("Title: Dracula", "Author: Bram Stoker", "Release date: October 1, 1995", "www.gutenberg.org/ebooks/345", "Contents", "A real sentence of the story begins here.")
        val book = EBook("t-front", "T", "A", "en", listOf(EChapter("The Project Gutenberg eBook of Dracula", paras), EChapter("Chapter 1", listOf("It was dark. The wind howled."))))
        val spoken = Plan.sentences(book, 0).map { Plan.chapterText(book.chapters[0]).substring(it.start, it.end) }
        assertTrue(spoken.any { it.startsWith("A real sentence") })
        assertTrue(spoken.none { it.startsWith("Title:") || it.startsWith("Author:") || it.contains("gutenberg.org") || it.equals("Contents", true) })
    }

    @Test fun speakTextCleansMarkup() {
        assertEquals("Hello world.", Plan.speakText("Hello _world_"))
        assertEquals("He left, and never came back.", Plan.speakText("He left\u2014and never came back."))
        assertEquals("See this.", Plan.speakText("See this [Illustration: a cat] [12]"))
    }

    @Test fun partForFindsTheRightPart() {
        val paras = (1..300).map { "Sentence one of paragraph $it. Sentence two. Sentence three." }
        val book = EBook("t-plan2", "T", "A", "en", listOf(EChapter("One", paras)))
        val parts = Plan.parts(book)
        assertEquals(parts.first().id, Plan.partFor(parts, 0, 0)?.id)
        assertEquals(parts.last().id, Plan.partFor(parts, 0, Int.MAX_VALUE)?.id)
        val mid = parts[parts.size / 2]
        assertEquals(mid.id, Plan.partFor(parts, 0, mid.start + 1)?.id)
    }
}
