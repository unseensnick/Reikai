package reikai.novel.content

/**
 * Inserts paragraph breaks into chapters that arrive as one unbroken wall of text, which some
 * sources do. Breaks land on sentence-ending punctuation, never mid-sentence.
 */
object NovelTextSplitter {

    private val sentenceEndingPunctuation = setOf('.', '!', '?', '。', '！', '？', '…')

    /**
     * Splits text by inserting paragraph breaks after approximately [wordCount] words, but always
     * continuing until a sentence-ending punctuation mark is found.
     *
     * @param text The input text (can be HTML or plain text)
     * @param wordCount Target number of words before looking for punctuation
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
            buildString { appendSplitting(line, this, wordsSincePunctuation = 0, targetWordCount, "\n\n") }
        }

    private fun splitHtmlText(html: String, targetWordCount: Int): String {
        val result = StringBuilder()
        var wordsSincePunctuation = 0

        var i = 0
        while (i < html.length) {
            if (html[i] == '<') {
                val tagEnd = html.indexOf('>', i)
                if (tagEnd == -1) {
                    result.append(html.substring(i))
                    break
                }
                val tag = html.substring(i, tagEnd + 1)
                result.append(tag)

                // A tag that already breaks the line restarts the count, so an existing paragraph
                // never gets a break inserted right after it.
                if (tag.lowercase().startsWith("<p>") ||
                    tag.lowercase().startsWith("<br") ||
                    tag.lowercase().startsWith("</p>") ||
                    tag.lowercase().startsWith("<div") ||
                    tag.lowercase().startsWith("</div") ||
                    tag.lowercase().startsWith("<body") ||
                    tag.lowercase().startsWith("</body")
                ) {
                    wordsSincePunctuation = 0
                }
                i = tagEnd + 1
            } else {
                val nextTag = html.indexOf('<', i)
                val textEnd = if (nextTag == -1) html.length else nextTag
                // Line breaks rather than paragraph tags, so the split stays valid inside div-based
                // chapters and body-level plain HTML.
                wordsSincePunctuation = appendSplitting(
                    html.substring(i, textEnd),
                    result,
                    wordsSincePunctuation,
                    targetWordCount,
                    "<br><br>",
                )
                i = textEnd
            }
        }

        return result.toString()
    }

    /**
     * Appends [text] to [out] with its whitespace intact, adding [breakMark] after the first sentence
     * end at or past [targetWordCount] words. Returns the running count, for a caller that carries it
     * across text runs.
     */
    private fun appendSplitting(
        text: String,
        out: StringBuilder,
        wordsSincePunctuation: Int,
        targetWordCount: Int,
        breakMark: String,
    ): Int {
        var count = wordsSincePunctuation
        var ti = 0
        while (ti < text.length) {
            val wsStart = ti
            while (ti < text.length && text[ti].isWhitespace()) ti++
            if (ti > wsStart) out.append(text, wsStart, ti)
            if (ti >= text.length) break

            val wordStart = ti
            while (ti < text.length && !text[ti].isWhitespace()) ti++
            out.append(text, wordStart, ti)
            count++

            if (text[ti - 1] in sentenceEndingPunctuation && count >= targetWordCount) {
                out.append(breakMark)
                count = 0
            }
        }
        return count
    }
}
