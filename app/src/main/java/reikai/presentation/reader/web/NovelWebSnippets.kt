package reikai.presentation.reader.web

import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import reikai.novel.content.NovelCodeSnippet
import reikai.novel.content.NovelSnippets

/**
 * The switched-on snippets a WebView chapter page carries: the CSS joined into one stylesheet, and the
 * JavaScript kept apart, since each runs on its own and a failing one must not stop the rest.
 */
data class NovelWebSnippets(
    val css: String = "",
    val js: List<NovelCodeSnippet> = emptyList(),
) {
    /** The JavaScript not yet run as it is now, going by what [ran] holds, keyed by id. */
    fun jsChangedSince(ran: Map<String, String>): List<NovelCodeSnippet> = js.filter { ran[it.id] != it.code }

    companion object {
        fun from(cssJson: String, jsJson: String) = NovelWebSnippets(
            css = NovelSnippets.decode(cssJson).filter { it.enabled }.joinToString("\n") { it.code },
            js = NovelSnippets.decode(jsJson).filter { it.enabled },
        )

        /**
         * Page script that runs each snippet as its own script element, so one that throws stops only
         * itself and dev tools name it. The code lands through `textContent`, which is never parsed as
         * markup, so no snippet can close a tag around it.
         */
        fun runner(snippets: List<NovelCodeSnippet>): String? {
            if (snippets.isEmpty()) return null
            val codes = snippets.joinToString(",") { snippet ->
                jsLiteral(snippet.code + "\n//# sourceURL=reikai-snippet-${sourceName(snippet)}.js")
            }
            return "[$codes].forEach(function (c) { var s = document.createElement('script'); " +
                "s.textContent = c; document.head.appendChild(s); s.remove(); });"
        }

        /**
         * [value] as a JavaScript string literal that can also sit inside a `<script>` element, which
         * the stylesheet does when the document is built: `<` is escaped, or a `</script>` in a snippet
         * would end the reader's own script, and `/`, or a snippet's comment close would end a comment
         * the stylesheet lands in.
         */
        fun jsLiteral(value: String): String =
            Json.encodeToString(String.serializer(), value).replace("<", "\\u003c").replace("/", "\\/")

        private fun sourceName(snippet: NovelCodeSnippet) = snippet.title.replace(Regex("[^A-Za-z0-9._-]"), "-")
    }
}
