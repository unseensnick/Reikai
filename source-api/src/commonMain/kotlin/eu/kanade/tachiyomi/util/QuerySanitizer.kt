package eu.kanade.tachiyomi.util

/**
 * Helpers for normalizing user-facing query strings before sending them to a source's
 * search API. Ported from Komikku to support the related-mangas keyword-search fallback.
 */
object QuerySanitizer {

    fun String.sanitize(removePrefix: String = ""): String {
        return trim()
            .removePrefix(removePrefix)
            .trim(*CHARACTER_TRIM_CHARS)
            .replaceSpecialChar()
    }

    // Replaces typographic characters with their plain ASCII equivalents.
    private fun String.replaceSpecialChar(): String {
        return replace('’', '\'') // right single quote
            .replace('‘', '\'') // left single quote
            .replace('“', '"') // left double quote
            .replace('”', '"') // right double quote
            .replace('–', '-') // en dash
            .replace('—', '-') // em dash
            .replace("…", "...") // horizontal ellipsis
    }

    private val CHARACTER_TRIM_CHARS: CharArray = intArrayOf(
        // Whitespace
        0x0020, // space
        0x0009, // tab
        0x000A, // line feed
        0x000B, // vertical tab
        0x000C, // form feed
        0x000D, // carriage return
        0x0085, // next line
        0x00A0, // no-break space
        0x1680, // ogham space mark
        0x2000, // en quad
        0x2001, // em quad
        0x2002, // en space
        0x2003, // em space
        0x2004, // three-per-em space
        0x2005, // four-per-em space
        0x2006, // six-per-em space
        0x2007, // figure space
        0x2008, // punctuation space
        0x2009, // thin space
        0x200A, // hair space
        0x2028, // line separator
        0x2029, // paragraph separator
        0x202F, // narrow no-break space
        0x205F, // medium mathematical space
        0x3000, // ideographic space

        // Separators
        0x002D, // -
        0x005F, // _
        0x002C, // ,
        0x003A, // :
    ).map { it.toChar() }.toCharArray()
}
