package exh.util

import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.domain.manga.model.Manga

/**
 * RK: long-strip detection behind auto webtoon mode, ported from Komikku (exh/util/MangaType),
 * which inherited it from TachiyomiSY. Classification reads an entry's genre tags and its source's
 * display name only, never image dimensions, so it costs nothing at page-load time.
 *
 * The tag tokens are not guesswork: the Madara and MangaThemesia extension themes scrape each
 * site's own series-type field and append it to the genre list, so several hundred sources emit
 * "Manga" / "Manhwa" / "Manhua" as a genre directly. That injection is what makes this work.
 *
 * [sourceName] is required rather than resolved from a SourceManager default, so this stays a pure
 * function: no DI to fail invisibly at the call site, and it is directly unit-testable.
 */
fun Manga.mangaType(sourceName: String?): MangaType {
    val currentTags = genre.orEmpty()
    return when {
        // The manga and comic branches run before manhua/manhwa deliberately: they suppress a
        // long-strip guess when the site itself calls the entry a manga or a comic, which is what
        // stops a mixed-content source from webtooning everything. They are not dead weight.
        currentTags.any { tag -> isMangaTag(tag) } -> {
            MangaType.TYPE_MANGA
        }
        currentTags.any { tag -> isWebtoonTag(tag) } || sourceName?.let { isWebtoonSource(it) } == true -> {
            MangaType.TYPE_WEBTOON
        }
        currentTags.any { tag -> isComicTag(tag) } || sourceName?.let { isComicSource(it) } == true -> {
            MangaType.TYPE_COMIC
        }
        currentTags.any { tag -> isManhuaTag(tag) } || sourceName?.let { isManhuaSource(it) } == true -> {
            MangaType.TYPE_MANHUA
        }
        currentTags.any { tag -> isManhwaTag(tag) } || sourceName?.let { isManhwaSource(it) } == true -> {
            MangaType.TYPE_MANHWA
        }
        else -> {
            MangaType.TYPE_MANGA
        }
    }
}

/**
 * The reading mode [type] implies, or null to leave the global default alone. Distinct from the
 * type itself: only the long-strip formats get a mode of their own.
 */
fun defaultReaderType(type: MangaType): Int? {
    return if (type in setOf(MangaType.TYPE_MANHUA, MangaType.TYPE_MANHWA, MangaType.TYPE_WEBTOON)) {
        ReadingMode.WEBTOON.flagValue
    } else {
        null
    }
}

private fun isMangaTag(tag: String): Boolean {
    return tag.contains("manga", true) ||
        tag.contains("манга", true)
}

private fun isManhuaTag(tag: String): Boolean {
    return tag.contains("manhua", true) ||
        tag.contains("маньхуа", true)
}

private fun isManhwaTag(tag: String): Boolean {
    return tag.contains("manhwa", true) ||
        tag.contains("манхва", true)
}

private fun isComicTag(tag: String): Boolean {
    return tag.contains("comic", true) ||
        tag.contains("комикс", true)
}

private fun isWebtoonTag(tag: String): Boolean {
    return tag.contains("long strip", true) ||
        tag.contains("webtoon", true)
}

private fun isManhwaSource(sourceName: String): Boolean {
    return sourceName.contains("hiperdex", true) ||
        sourceName.contains("hmanhwa", true) ||
        sourceName.contains("instamanhwa", true) ||
        sourceName.contains("manhwa18", true) ||
        sourceName.contains("manhwa68", true) ||
        sourceName.contains("manhwa365", true) ||
        sourceName.contains("manhwahentaime", true) ||
        sourceName.contains("manhwamanga", true) ||
        sourceName.contains("manhwatop", true) ||
        sourceName.contains("manhwa club", true) ||
        sourceName.contains("manytoon", true) ||
        sourceName.contains("manwha", true) ||
        sourceName.contains("readmanhwa", true) ||
        sourceName.contains("skymanga", true) ||
        sourceName.contains("toonily", true) ||
        sourceName.contains("webtoonxyz", true)
}

private fun isWebtoonSource(sourceName: String): Boolean {
    return sourceName.contains("mangatoon", true) ||
        sourceName.contains("manmanga", true) ||
        sourceName.contains("toomics", true) ||
        sourceName.contains("webcomics", true) ||
        sourceName.contains("webtoons", true) ||
        sourceName.contains("webtoon", true)
}

private fun isComicSource(sourceName: String): Boolean {
    return sourceName.contains("8muses", true) ||
        sourceName.contains("allporncomic", true) ||
        sourceName.contains("ciayo comics", true) ||
        sourceName.contains("comicextra", true) ||
        sourceName.contains("comicpunch", true) ||
        sourceName.contains("cyanide", true) ||
        sourceName.contains("dilbert", true) ||
        sourceName.contains("eggporncomics", true) ||
        sourceName.contains("existential comics", true) ||
        sourceName.contains("hiveworks comics", true) ||
        sourceName.contains("milftoon", true) ||
        sourceName.contains("myhentaicomics", true) ||
        sourceName.contains("myhentaigallery", true) ||
        sourceName.contains("gunnerkrigg", true) ||
        sourceName.contains("oglaf", true) ||
        sourceName.contains("patch friday", true) ||
        sourceName.contains("porncomix", true) ||
        sourceName.contains("questionable content", true) ||
        sourceName.contains("readcomiconline", true) ||
        sourceName.contains("read comics online", true) ||
        sourceName.contains("swords comic", true) ||
        sourceName.contains("teabeer comics", true) ||
        sourceName.contains("xkcd", true)
}

private fun isManhuaSource(sourceName: String): Boolean {
    return sourceName.contains("1st kiss manhua", true) ||
        sourceName.contains("hero manhua", true) ||
        sourceName.contains("manhuabox", true) ||
        sourceName.contains("manhuaus", true) ||
        sourceName.contains("manhuas world", true) ||
        sourceName.contains("manhuas.net", true) ||
        sourceName.contains("readmanhua", true) ||
        sourceName.contains("wuxiaworld", true) ||
        sourceName.contains("manhua", true)
}

enum class MangaType {
    TYPE_MANGA,
    TYPE_MANHWA,
    TYPE_MANHUA,
    TYPE_COMIC,
    TYPE_WEBTOON,
}
