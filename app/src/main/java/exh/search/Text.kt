package exh.search

class Text : QueryComponent() {
    val components = mutableListOf<TextComponent>()

    private var rawText: String? = null

    // Build a case-insensitive regex from the components so wildcards actually match in the
    // in-memory library filter. (Komikku only honours wildcards in its browse SQL path; in memory
    // it passes the raw LIKE pattern to String.contains, so `*`/`?` degrade to literal characters.)
    // Literal text is regex-escaped; `?`/`_` match any single char, `*`/`%` match any run. When
    // [exact] (a $-prefixed term) the pattern is anchored to the whole value, else it matches as a
    // substring.
    fun asRegex(exact: Boolean = false): Regex {
        val pattern = buildString {
            for (component in components) {
                when (component) {
                    is SingleWildcard -> append('.')
                    is MultiWildcard -> append(".*")
                    is StringTextComponent -> append(Regex.escape(component.value))
                    else -> append(Regex.escape(component.rawText))
                }
            }
        }
        return if (exact) {
            Regex("^$pattern$", RegexOption.IGNORE_CASE)
        } else {
            Regex(pattern, RegexOption.IGNORE_CASE)
        }
    }

    fun rawTextOnly(): String =
        rawText ?: components.joinToString(separator = "") { it.rawText }.also { rawText = it }
}
