package reikai.domain.library

import java.util.Locale

/**
 * A local book's language tag (EPUB `dc:language`, ComicInfo `LanguageISO`, `details.json`) as the code the
 * sources use, so a local series groups and searches with them. A region is kept only where sources split
 * by it (Brazilian Portuguese, Latin American Spanish, Simplified and Traditional Chinese); otherwise just
 * the language. A three-letter code reads as its two-letter one, and `mul` as the sources' multi-language
 * code, the inverse of [bookLanguage]. Null for a blank or undetermined tag.
 */
fun localLanguage(tag: String?): String? {
    val locale = tag?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag)
        ?: return null
    val language = locale.language.takeIf { it.isNotEmpty() && it != "und" }
        ?.let { TWO_LETTER_CODES[it] ?: it }
        ?: return null
    return when (language) {
        "mul" -> MULTI_LANGUAGE
        "pt" -> if (locale.country == "BR") "pt-BR" else "pt"
        "es" -> if (locale.country == "419") "es-419" else "es"
        "zh" -> when {
            locale.script == "Hans" || locale.country in setOf("CN", "SG") -> "zh-Hans"
            locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO") -> "zh-Hant"
            else -> "zh"
        }
        else -> language
    }
}

/**
 * The tag a book exported from an entry carries for its [language]: the multi-language code becomes BCP 47's
 * `mul`, since `all` is a real ISO 639-3 language. Null when the entry names none, or only the local source's
 * placeholder.
 */
fun bookLanguage(language: String): String? = when (language) {
    "", "other" -> null
    MULTI_LANGUAGE -> "mul"
    else -> language
}

/**
 * The language a library row shows and searches by: a local entry's own, read from its files, since a
 * local source's language is a placeholder; any other entry's source language. Empty when a local entry's
 * files name none.
 */
fun entryLanguage(isLocal: Boolean, own: String?, source: String): String =
    if (isLocal) own.orEmpty() else source

// Mihon's code for a source serving several languages, which the novel sources map their Multi onto.
private const val MULTI_LANGUAGE = "all"

// ISO 639-2 terminology codes from the JDK, plus the bibliographic codes EPUB 2 books also carry.
private val TWO_LETTER_CODES: Map<String, String> by lazy {
    val terminology = Locale.getISOLanguages().mapNotNull { code ->
        runCatching { Locale.forLanguageTag(code).isO3Language }.getOrNull()?.takeIf { it.isNotEmpty() }?.to(code)
    }
    terminology.toMap() + mapOf(
        "alb" to "sq", "arm" to "hy", "baq" to "eu", "bur" to "my", "chi" to "zh", "cze" to "cs", "dut" to "nl",
        "fre" to "fr", "geo" to "ka", "ger" to "de", "gre" to "el", "ice" to "is", "mac" to "mk", "mao" to "mi",
        "may" to "ms", "per" to "fa", "rum" to "ro", "slo" to "sk", "tib" to "bo", "wel" to "cy",
    )
}
