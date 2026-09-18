package reikai.presentation.reader

import android.content.Context
import eu.kanade.presentation.util.formatChapterNumber
import reikai.domain.reader.ChapterTitleFormat
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/** The words the bar puts around a chapter number, from one place so both readers say the same. */
interface ChapterTitleWords {
    fun numbered(number: String): String

    fun numberedWithName(number: String, name: String): String
}

/** [ChapterTitleWords] in the app's language. */
fun Context.chapterTitleWords(): ChapterTitleWords = object : ChapterTitleWords {
    override fun numbered(number: String) = stringResource(MR.strings.display_mode_chapter, number)

    override fun numberedWithName(number: String, name: String) =
        stringResource(MR.strings.chapter_title_numbered, number, name)
}

/**
 * The open chapter's title as the bar shows it in this format. A chapter without a number keeps its name
 * whatever the format, since a source that numbers nothing stores a negative one.
 */
fun ChapterTitleFormat.chapterTitle(name: String, number: Double, words: ChapterTitleWords): String {
    if (this == ChapterTitleFormat.NAME || number < 0) return name
    val shown = formatChapterNumber(number)
    if (this == ChapterTitleFormat.NUMBER) return words.numbered(shown)
    val rest = name.withoutLeadingNumber(shown)
    return if (rest.isEmpty()) words.numbered(shown) else words.numberedWithName(shown, rest)
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
