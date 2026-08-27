package reikai.novel.source

/**
 * The language a plugin declares, as the ISO code the rest of the app speaks.
 *
 * The lnreader registry is not consistent with itself: most plugins name the language in English
 * ("Spanish"), a few give the code ("es"), and both forms are live. Left as-is they group as two
 * different languages, and beside the manga sources, which are all codes, they group as a third.
 */
fun NovelSource.langCode(): String = LANGUAGE_CODES[lang] ?: lang

private val LANGUAGE_CODES = mapOf(
    "Arabic" to "ar",
    "English" to "en",
    "French" to "fr",
    "Indonesian" to "id",
    "Korean" to "ko",
    "Portuguese" to "pt",
    "Russian" to "ru",
    "Spanish" to "es",
    "Thai" to "th",
    "Turkish" to "tr",
)
