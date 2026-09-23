package reikai.domain.source

/**
 * Whether a cover address is a lazy-loading placeholder rather than a cover: some site themes put one in
 * the image's `src` until a script swaps the real address in, and a source reading `src` passes it on.
 * Each name was seen from a real source, never guessed, since a match keeps an older cover.
 */
fun isPlaceholderCover(url: String): Boolean =
    url.startsWith("data:image/svg", ignoreCase = true) ||
        url.substringBefore('?').substringAfterLast('/').substringBeforeLast('.').lowercase() in PLACEHOLDER_NAMES

// Madara's `themes/madara/images/dflazy.jpg`; the theme's other placeholder is the inline SVG above.
private val PLACEHOLDER_NAMES = setOf("dflazy")

/** The cover a parse leaves stored: [parsed], unless it is blank or a placeholder, then [current]. */
fun keptCover(current: String?, parsed: String?): String? =
    parsed?.takeIf { it.isNotBlank() && !isPlaceholderCover(it) } ?: current
