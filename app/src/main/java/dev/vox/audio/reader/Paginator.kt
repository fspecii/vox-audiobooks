package dev.vox.audio.reader

import android.graphics.Typeface
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AlignmentSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan

/** One page = a range of lines of a chapter layout; text is drawn straight from the layout so highlights line up exactly. */
class PageSpec(val chapter: Int, val layout: StaticLayout, val startLine: Int, val endLine: Int) {
    val charStart get() = layout.getLineStart(startLine)
    val charEnd get() = layout.getLineEnd(endLine - 1)
    val top get() = layout.getLineTop(startLine).toFloat()
    val bottom get() = layout.getLineBottom(endLine - 1).toFloat()
}

class Paged(val pages: List<PageSpec>, val plain: List<String>, val firstPage: IntArray) {
    fun pageOf(chapter: Int, offset: Int): Int {
        val from = firstPage.getOrElse(chapter) { 0 }
        var i = from
        while (i < pages.size && pages[i].chapter == chapter) { if (offset < pages[i].charEnd) return i; i++ }
        return (i - 1).coerceIn(0, (pages.size - 1).coerceAtLeast(0))
    }
}

object Paginator {
    fun build(book: EBook, widthPx: Int, heightPx: Int, textPx: Float, serif: Boolean, color: Int): Paged {
        val paint = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = textPx; typeface = if (serif) Typeface.SERIF else Typeface.SANS_SERIF
        }
        val pages = ArrayList<PageSpec>(); val plain = ArrayList<String>(); val first = IntArray(book.chapters.size)
        val indent = (textPx * 1.4f).toInt()
        book.chapters.forEachIndexed { ci, ch ->
            val sb = SpannableStringBuilder()
            sb.append(ch.title)
            sb.setSpan(StyleSpan(Typeface.BOLD), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(RelativeSizeSpan(1.25f), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(AlignmentSpan.Standard(Layout.Alignment.ALIGN_CENTER), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.append("\n \n")
            for (p in ch.paras) {
                val s = sb.length
                sb.append(p).append('\n')
                sb.setSpan(LeadingMarginSpan.Standard(indent, 0), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val layout = StaticLayout.Builder.obtain(sb, 0, sb.length, paint, widthPx)
                .setLineSpacing(0f, 1.18f).setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setJustificationMode(Layout.JUSTIFICATION_MODE_INTER_WORD).build()
            plain.add(sb.toString()); first[ci] = pages.size
            var line = 0
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var end = line + 1
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= heightPx) end++
                pages.add(PageSpec(ci, layout, line, end)); line = end
            }
        }
        return Paged(pages, plain, first)
    }
}
