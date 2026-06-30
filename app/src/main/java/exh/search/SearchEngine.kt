package exh.search

import java.util.Locale

/**
 * Parses an E-Hentai-style tag query into [QueryComponent]s for the library tag search.
 *
 * Ported from Komikku, trimmed to the parsing half: the SQL-emitting `queryToSql` path is a
 * browse-side concern, so the library matches the parsed components in memory (see
 * `LibraryItem.matches`). Supports `namespace:tag` with aliases, quoted phrases, `-` exclusion,
 * `$` exact, and `*`/`?` (and `%`/`_`) wildcards.
 */
class SearchEngine {
    private val queryCache = mutableMapOf<String, List<QueryComponent>>()

    fun parseQuery(query: String, enableWildcard: Boolean = true) = queryCache.getOrPut(query) {
        val res = mutableListOf<QueryComponent>()

        var inQuotes = false
        val queuedRawText = StringBuilder()
        val queuedText = mutableListOf<TextComponent>()
        var namespace: Namespace? = null

        var nextIsExcluded = false
        var nextIsExact = false

        fun flushText() {
            if (queuedRawText.isNotEmpty()) {
                queuedText += StringTextComponent(queuedRawText.toString())
                queuedRawText.setLength(0)
            }
        }

        fun flushToText() = Text().apply {
            components += queuedText
            queuedText.clear()
        }

        fun flushAll() {
            flushText()
            if (queuedText.isNotEmpty() || namespace != null) {
                val component = namespace?.apply {
                    tag = flushToText()
                    namespace = null
                } ?: flushToText()
                component.excluded = nextIsExcluded
                component.exact = nextIsExact
                res += component
                nextIsExcluded = false
                nextIsExact = false
            }
        }

        query.lowercase(Locale.getDefault()).forEach { char ->
            if (char == '"') {
                inQuotes = !inQuotes
            } else if (enableWildcard && (char == '?' || char == '_')) {
                flushText()
                queuedText.add(SingleWildcard(char.toString()))
            } else if (enableWildcard && (char == '*' || char == '%')) {
                flushText()
                queuedText.add(MultiWildcard(char.toString()))
            } else if (char == '-' && !inQuotes && (queuedRawText.isBlank() || queuedRawText.last() == ' ')) {
                nextIsExcluded = true
            } else if (char == '$') {
                nextIsExact = true
            } else if (char == ':') {
                flushText()
                var flushed = flushToText().rawTextOnly()
                // Map tag aliases
                flushed = when (flushed) {
                    "a" -> "artist"
                    "c", "char" -> "character"
                    "f" -> "female"
                    "g", "creator", "circle" -> "group"
                    "l", "lang" -> "language"
                    "m" -> "male"
                    "p", "series" -> "parody"
                    "r" -> "reclass"
                    else -> flushed
                }
                namespace = Namespace(flushed, null)
            } else if (arrayOf(' ', ',').contains(char) && !inQuotes) {
                flushAll()
            } else {
                queuedRawText.append(char)
            }
        }
        flushAll()

        res
    }
}
