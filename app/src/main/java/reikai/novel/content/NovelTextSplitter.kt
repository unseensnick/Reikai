package reikai.novel.content

import org.jsoup.parser.Parser
import reikai.domain.novel.text.NovelWords
import reikai.domain.novel.text.TextSegments
import java.util.Locale

/**
 * Inserts paragraph breaks into chapters that arrive as one unbroken wall of text, which some
 * sources do. Breaks land at sentence ends, by the same sentence rule read-aloud speaks by.
 */
object NovelTextSplitter {

    /**
     * Splits text by inserting paragraph breaks after approximately [wordCount] words, but always
     * continuing to the end of the sentence. A paragraph's last sentence gets no break, since a
     * paragraph break already follows it.
     *
     * @param text The input text (can be HTML or plain text)
     * @param wordCount Target number of words before looking for a sentence end
     * @param isHtml Whether [text] is HTML markup, per the caller's own classification
     * @return Text with additional paragraph breaks inserted
     */
    fun splitText(text: String, wordCount: Int, isHtml: Boolean): String {
        if (wordCount <= 0) return text
        val effectiveWordCount = wordCount.coerceAtLeast(20)

        return if (isHtml) {
            // Each stretch between verbatim blocks is counted afresh, so a block also ends a paragraph.
            NovelHtmlUtils.mapOutsideVerbatimBlocks(text) { splitHtmlText(it, effectiveWordCount) }
        } else {
            splitPlainText(text, effectiveWordCount)
        }
    }

    // Each line is walked afresh, so an existing break restarts the count and survives as it came.
    private fun splitPlainText(text: String, targetWordCount: Int): String =
        text.split('\n').joinToString("\n") { line ->
            insertAt(line, sentenceBreaks(line, targetWordCount), "\n\n")
        }

    private fun splitHtmlText(html: String, targetWordCount: Int): String {
        val breaks = mutableListOf<Int>()
        // The text runs of the paragraph being read, as offsets in [html]; inline tags between them
        // do not end a sentence.
        val runs = mutableListOf<Pair<Int, Int>>()

        var i = 0
        while (i < html.length) {
            if (html[i] == '<') {
                val tagEnd = html.indexOf('>', i)
                if (tagEnd == -1) break
                val tag = html.substring(i, tagEnd + 1).lowercase()
                // A tag that already breaks the line restarts the count, so an existing paragraph
                // never gets a break inserted right after it.
                if (lineBreakingTags.any { tag.startsWith(it) }) {
                    breaks += htmlBreaks(html, runs, targetWordCount)
                    runs.clear()
                }
                i = tagEnd + 1
            } else {
                val nextTag = html.indexOf('<', i)
                val textEnd = if (nextTag == -1) html.length else nextTag
                runs += i to textEnd
                i = textEnd
            }
        }
        breaks += htmlBreaks(html, runs, targetWordCount)

        // Line breaks rather than paragraph tags, so the split stays valid inside div-based chapters
        // and body-level plain HTML.
        return insertAt(html, breaks, "<br><br>")
    }

    private val lineBreakingTags = listOf("<p>", "<br", "</p>", "<div", "</div", "<body", "</body")

    /**
     * Offsets in [html] a break goes at, for the paragraph made of [runs]. Its text is read with each
     * entity decoded, so an escaped closing quote stays with its sentence, and each character
     * remembers where its source ends in [html].
     */
    private fun htmlBreaks(html: String, runs: List<Pair<Int, Int>>, targetWordCount: Int): List<Int> {
        val prose = StringBuilder()
        val sourceEnd = IntArray(runs.sumOf { (start, end) -> end - start })
        for ((start, end) in runs) {
            var i = start
            while (i < end) {
                val entity = if (html[i] == '&') NovelHtmlUtils.entityRegex.matchAt(html, i)?.value else null
                val decoded = entity?.let { Parser.unescapeEntities(it, false) }
                if (entity != null && decoded != null && decoded != entity) {
                    i += entity.length
                    decoded.forEach {
                        sourceEnd[prose.length] = i
                        prose.append(it)
                    }
                } else {
                    // A page collapses whitespace, so a line break in the source is no paragraph end,
                    // which Android's sentence rule would otherwise take it for.
                    sourceEnd[prose.length] = ++i
                    prose.append(if (html[i - 1].isWhitespace()) ' ' else html[i - 1])
                }
            }
        }
        return sentenceBreaks(prose.toString(), targetWordCount).map { sourceEnd[it - 1] }
    }

    /**
     * Offsets in [text] just past each sentence a break follows: the first sentence end at or past
     * [targetWordCount] words, counted afresh after each break, and never the last sentence.
     */
    private fun sentenceBreaks(text: String, targetWordCount: Int): List<Int> {
        val sentences = TextSegments.sentences(text, 0, text.length, Locale.ROOT)
        val breaks = mutableListOf<Int>()
        var words = 0
        for ((index, sentence) in sentences.withIndex()) {
            val (start, end) = sentence
            words += NovelWords.count(text, start, end)
            if (words >= targetWordCount && index < sentences.lastIndex) {
                // Before the whitespace a sentence carries, so the break sits against its last word.
                var last = end
                while (last > start && text[last - 1].isWhitespace()) last--
                breaks += last
                words = 0
            }
        }
        return breaks
    }

    private fun insertAt(text: String, offsets: List<Int>, mark: String): String {
        if (offsets.isEmpty()) return text
        val out = StringBuilder(text.length + offsets.size * mark.length)
        var from = 0
        for (offset in offsets) {
            out.append(text, from, offset).append(mark)
            from = offset
        }
        return out.append(text, from, text.length).toString()
    }
}
