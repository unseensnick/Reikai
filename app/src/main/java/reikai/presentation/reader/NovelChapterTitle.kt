package reikai.presentation.reader

import eu.kanade.presentation.util.formatChapterNumber
import reikai.domain.novel.NovelChapterTitleFormat

/**
 * The open chapter's title as the bar shows it in this format. A chapter without a number keeps its name
 * whatever the format, since a source that numbers nothing stores a negative one. [numbered] and
 * [numberedWithName] put the words around the number, which the caller resolves from resources.
 */
fun NovelChapterTitleFormat.chapterTitle(
    name: String,
    number: Double,
    numbered: (number: String) -> String,
    numberedWithName: (number: String, name: String) -> String,
): String {
    if (this == NovelChapterTitleFormat.NAME || number < 0) return name
    val shown = formatChapterNumber(number)
    if (this == NovelChapterTitleFormat.NUMBER) return numbered(shown)
    val rest = name.withoutLeadingNumber(shown)
    return if (rest.isEmpty()) numbered(shown) else numberedWithName(shown, rest)
}

/**
 * The name with the chapter number it opens with taken off, so "Chapter 3: The Duel" does not read
 * "Ch. 3: Chapter 3: The Duel". Sources often repeat the number ("Chapter 3 3: The Duel"), hence twice.
 */
private fun String.withoutLeadingNumber(number: String): String {
    val prefix =
        Regex(
            """^\s*(?:ch(?:apter)?\.?\s*)?#?0*${Regex.escape(number)}(?!\d|\.\d)\s*[:.\-–—]?\s*""",
            RegexOption.IGNORE_CASE,
        )
    return replaceFirst(prefix, "").replaceFirst(prefix, "")
}
