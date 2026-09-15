package reikai.presentation.reader

/**
 * A stored page colour as an ARGB int, read the way the WebView page's CSS reads it: `#rgb`, `#rgba`,
 * `#rrggbb` or `#rrggbbaa`, alpha last. Null for anything else, which CSS would drop. Both renderers and
 * the page background ask this, so a value one of them draws is the value the other draws.
 */
fun readerColorOrNull(value: String): Int? {
    if (!value.startsWith("#")) return null
    val digits = value.substring(1)
    if (digits.any { Character.digit(it, 16) < 0 }) return null
    val full = when (digits.length) {
        3, 4 -> digits.flatMap { listOf(it, it) }.joinToString("")
        6, 8 -> digits
        else -> return null
    }
    val rgb = full.substring(0, 6).toLong(16)
    val alpha = if (full.length == 8) full.substring(6).toLong(16) else 0xFF
    return ((alpha shl 24) or rgb).toInt()
}

/** The page background [value] names, or the reader's own dark default when it names none. */
fun readerBackgroundColorInt(value: String): Int =
    readerColorOrNull(value) ?: readerColorOrNull(readerDarkPreset.background)!!

/** The text colour [value] names, or the reader's own dark default when it names none. */
fun readerTextColorInt(value: String): Int = readerColorOrNull(value) ?: readerColorOrNull(readerDarkPreset.textColor)!!
