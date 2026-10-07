package reikai.domain.source

/** A source's name with its language code beside it, as Mihon labels a manga source, for either content type. */
fun sourceVisualName(name: String, lang: String): String = when {
    lang.isEmpty() -> name
    else -> "$name (${lang.uppercase()})"
}
