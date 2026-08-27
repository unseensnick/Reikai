package reikai.novel.source

/**
 * The language a plugin declares, as the ISO code the rest of the app speaks.
 *
 * An lnreader registry names the language in the language itself ("Español", "Русский"), so left
 * as-is a plugin never groups with the manga sources of its language, and Android cannot resolve the
 * name to a heading at all. Anything unrecognised passes through rather than being dropped.
 */
fun String.toLangCode(): String {
    // Arabic arrives with a leading left-to-right mark, which is invisible and breaks the lookup.
    val declared = filterNot { it.category == CharCategory.FORMAT }.trim()
    return LANGUAGE_CODES[declared] ?: declared
}

/** The declared language of an installed plugin, normalised by [toLangCode]. */
fun NovelSource.langCode(): String = lang.toLangCode()

// The registry's own name-to-language table (lnreader-plugins `scripts/languages.js`), inverted.
// Multi maps onto Mihon's "all", so a multi-language plugin shares the manga sources' Multi section.
private val LANGUAGE_CODES = mapOf(
    "العربية" to "ar",
    "中文, 汉语, 漢語" to "zh",
    "English" to "en",
    "Français" to "fr",
    "Bahasa Indonesia" to "id",
    "日本語" to "ja",
    "조선말, 한국어" to "ko",
    "Polski" to "pl",
    "Português" to "pt",
    "Русский" to "ru",
    "Español" to "es",
    "ไทย" to "th",
    "Türkçe" to "tr",
    "Українська" to "uk",
    "Tiếng Việt" to "vi",
    "Multi" to "all",
)
