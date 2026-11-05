package reikai.domain.library

import java.util.Locale

/**
 * A local book's language tag (EPUB `dc:language`, ComicInfo `LanguageISO`, `details.json`) as the code the
 * sources use, so a local series groups and searches with them. A region is kept only where sources split
 * by it (Brazilian Portuguese, Latin American Spanish, Simplified and Traditional Chinese); otherwise just
 * the language. Null for a blank or undetermined tag.
 */
fun localLanguage(tag: String?): String? {
    val locale = tag?.trim()?.replace('_', '-')?.takeIf { it.isNotEmpty() }?.let(Locale::forLanguageTag)
        ?: return null
    val language = locale.language.takeIf { it.isNotEmpty() && it != "und" && it != "mul" } ?: return null
    return when (language) {
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
 * The language a library row shows and searches by: a local entry's own, read from its files, since a
 * local source's language is a placeholder; any other entry's source language. Empty when a local entry's
 * files name none.
 */
fun entryLanguage(isLocal: Boolean, own: String?, source: String): String =
    if (isLocal) own.orEmpty() else source
