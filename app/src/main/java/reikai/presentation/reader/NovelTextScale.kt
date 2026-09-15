package reikai.presentation.reader

/**
 * How much larger or smaller than the body text each sized element is set, once for both novel
 * renderers: the WebView page's stylesheet is written from it, and the native renderer resizes
 * `Html.fromHtml`'s own spans to it. The values are a browser's defaults, which the page already used.
 */
internal object NovelTextScale {

    /** `h1` to `h6`. */
    val headings = listOf(2f, 1.5f, 1.17f, 1f, 0.83f, 0.67f)
    const val SMALL = 0.83f

    /** The page sets every element's size to its parent's, `big` included, so it is body size there. */
    const val BIG = 1f
    const val SCRIPT = 0.7f
    const val RUBY_READING = 0.5f

    /**
     * The table's size for a relative size `Html.fromHtml` gave an element, or null for one it did not
     * give. Its values are fixed in the framework (`HEADING_SIZES`, and `big` and `small`) and each
     * names one element, so the value alone says which.
     */
    fun forFromHtmlSize(size: Float): Float? = when (size) {
        in fromHtmlHeadings -> headings[fromHtmlHeadings.indexOf(size)]
        FROM_HTML_BIG -> BIG
        FROM_HTML_SMALL -> SMALL
        else -> null
    }

    private val fromHtmlHeadings = listOf(1.5f, 1.4f, 1.3f, 1.2f, 1.1f, 1f)
    private const val FROM_HTML_BIG = 1.25f
    private const val FROM_HTML_SMALL = 0.8f
}
