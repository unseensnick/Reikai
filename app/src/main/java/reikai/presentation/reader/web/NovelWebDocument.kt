package reikai.presentation.reader.web

import android.content.Context
import com.google.android.material.color.MaterialColors
import org.json.JSONObject
import reikai.presentation.reader.NovelReaderSettings
import reikai.presentation.reader.NovelTextScale
import reikai.presentation.reader.text.CHAPTER_IMAGE_WAIT_MS
import reikai.presentation.reader.text.NovelChapterSeamView
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import java.util.Locale

/**
 * The document the WebView rendering mode renders, assembled here because it is this renderer's own
 * format. Its stylesheet and engine are inlined from `assets/novel-web/`.
 *
 * Every settings-derived value goes through the sanitisers in `NovelReaderCssValues`, since a
 * restored backup can put any string in a preference and this block runs in a page holding the
 * app's cookies.
 */
object NovelWebDocument {

    fun build(
        context: Context,
        chapterId: Long,
        /** Echoed by the page's ready report, so only this document's engine can open the host's gate. */
        documentToken: String,
        chapterHtml: String,
        initialFraction: Float,
        /** The line to land on in its place, as `LoadedChapter.topLine`. */
        initialLine: Int? = null,
        settings: NovelReaderSettings,
        statusBarHeightPx: Int,
        /** The chosen face as a `data:` URI from [NovelWebFonts], or null for a generic family. */
        fontSource: String?,
        useOriginalFonts: Boolean,
        sourceCssPriority: Boolean,
        textSelectable: Boolean,
    ): String {
        val css = NovelWebAssets.read(context, "reader.css")
        val js = NovelWebAssets.readWith(
            context,
            "reader.js",
            mapOf(
                "__SWIPE__" to settings.swipeGestures.toString(),
                "__BIONIC__" to settings.bionicReading.toString(),
                "__READ_ALOUD__" to readAloudJson(settings).toString(),
                "__INITIAL_FRACTION__" to initialFraction.coerceIn(0f, 1f).toString(),
                "__INITIAL_LINE__" to (initialLine?.coerceAtLeast(0) ?: -1).toString(),
                "__IMAGE_WAIT_MS__" to CHAPTER_IMAGE_WAIT_MS.toString(),
                "__DOCUMENT_TOKEN__" to jsString(documentToken),
                // The seam names both chapters under these, the way TransitionText does. Resolved
                // here because the page has no resources of its own.
                "__LABEL_FINISHED__" to jsString(context.stringResource(MR.strings.transition_finished)),
                "__LABEL_NEXT__" to jsString(context.stringResource(MR.strings.transition_next)),
                "__LABEL_NO_NEXT__" to jsString(context.stringResource(MR.strings.transition_no_next)),
                "__LABEL_DOWNLOADED__" to jsString(context.stringResource(MR.strings.label_downloaded)),
                "__LABEL_IMAGE_ERROR__" to jsString(context.stringResource(MR.strings.decode_image_error)),
                "__LABEL_RETRY__" to jsString(context.stringResource(MR.strings.action_retry)),
                // Last, because the tokens are replaced in order and a stylesheet naming one of the
                // tokens above would otherwise have it filled in, the document token included.
                "__CSS_SNIPPETS__" to NovelWebSnippets.jsLiteral(settings.webSnippets.css),
            ),
        )
        // The engine is in the head so it runs before the chapter: it holds the bridge and its token
        // before any script the chapter carries can reach them, and a chapter whose markup never
        // closes (a stray `<plaintext>`) cannot swallow the engine as text.
        // Built line by line rather than trimmed: the chapter is megabytes with its images inlined, and
        // trimming the whole document copied it for nothing, since its own lines carry no indent.
        return buildString {
            appendLine("<!DOCTYPE html>")
            appendLine("<html>")
            appendLine("<head>")
            appendLine("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1, maximum-scale=1\">")
            appendLine("<style id=\"rk-font-face\">${fontFace(settings.fontFamily, fontSource)}</style>")
            appendLine("<style>")
            appendLine(":root { ${variables(settings, statusBarHeightPx)} ${chromeVariables(context)} }")
            appendLine(css)
            appendLine(overrides(useOriginalFonts, sourceCssPriority))
            appendLine(if (textSelectable) "" else "body { -webkit-user-select: none; user-select: none; }")
            appendLine("</style>")
            appendLine("<style id=\"rk-snippets\"></style>")
            append("<script>").append(js).appendLine("</script>")
            appendLine("</head>")
            appendLine("<body>")
            appendLine("<div id=\"rk-chapters\">")
            append("<div class=\"rk-chapter\" data-rk-chapter-id=\"").append(chapterId).append("\">")
            append(chapterHtml).appendLine("</div>")
            appendLine("</div>")
            appendLine("</body>")
            append("</html>")
        }
    }

    /**
     * The custom properties the stylesheet reads. Rewriting these is how a settings change reflows in
     * place, so every value a setting can move is one of them rather than baked into the document.
     */
    fun variables(settings: NovelReaderSettings, statusBarHeightPx: Int): String = buildString {
        append("--rk-background:").append(cssBackgroundColor(settings.backgroundColor)).append(';')
        append("--rk-text:").append(cssTextColor(settings.textColor)).append(';')
        append("--rk-font-size:").append(settings.fontSize).append("px;")
        append("--rk-line-height:").append(settings.lineHeight).append(';')
        append("--rk-text-align:").append(cssTextAlign(settings.textAlign)).append(';')
        // The Default font is the device's own, which is what the native renderer draws it as
        // (Typeface.DEFAULT) and what its summary says. A serif fallback drew a different face in
        // each rendering mode from one setting.
        append("--rk-font-family:")
            .append(cssFontFamilyValue(settings.fontFamily).ifEmpty { "sans-serif" })
            .append(';')
        append("--rk-margin-top:").append(settings.margins.top).append("px;")
        append("--rk-margin-bottom:").append(settings.margins.bottom).append("px;")
        append("--rk-margin-left:").append(settings.margins.left).append("px;")
        append("--rk-margin-right:").append(settings.margins.right).append("px;")
        append("--rk-paragraph-indent:").append(settings.paragraphIndent).append("em;")
        append("--rk-paragraph-spacing:").append(settings.paragraphSpacing).append("em;")
        append("--rk-inset-top:").append(statusBarHeightPx).append("px;")
    }

    /**
     * What the page's own markers are drawn with and no setting moves, so a settings push, which
     * rewrites [variables] only, leaves them alone. The seam's padding is the text renderer's (its
     * seam view), written once; the error and primary colours are the app theme's, as the text
     * renderer's gap warning and no-next notice draw their icons in them.
     */
    private fun chromeVariables(context: Context): String = buildString {
        append("--rk-seam-padding-vertical:").append(NovelChapterSeamView.PADDING_VERTICAL_DP).append("px;")
        append("--rk-seam-padding-horizontal:").append(NovelChapterSeamView.PADDING_HORIZONTAL_DP).append("px;")
        // Both attrs are read off appcompat rather than material: material 1.14.0 stopped declaring
        // colorError and colorPrimary in its own R, keeping only the ones it owns (colorOnBackground).
        val error = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorError, FALLBACK_ERROR)
        append("--rk-error:").append(cssHex(error)).append(';')
        val primary = MaterialColors.getColor(
            context,
            androidx.appcompat.R.attr.colorPrimary,
            FALLBACK_PRIMARY,
        )
        append("--rk-primary:").append(cssHex(primary)).append(';')
    }

    private fun cssHex(color: Int): String = "#%06X".format(Locale.ROOT, color and 0xFFFFFF)

    /** Material's baseline error red and primary purple, for a context whose theme names neither. */
    private const val FALLBACK_ERROR = 0xFFB3261E.toInt()
    private const val FALLBACK_PRIMARY = 0xFF6750A4.toInt()

    /**
     * How the reader's display settings hold their ground against a chapter's own CSS, which wins a tie
     * on document order, hence `!important`. Ported from tsundoku's `fontOverrideCss`: forcing
     * `font-size: inherit` everywhere flattens headings, so they are restated, and so are footnote
     * markers and ruby readings, which tsundoku left at body size. A failed picture's box keeps its own
     * look through `:where`, which adds no weight, so the sized elements below still win on weight.
     */
    private fun overrides(useOriginalFonts: Boolean, sourceCssPriority: Boolean): String {
        if (sourceCssPriority) return ""
        val family = if (useOriginalFonts) "" else "font-family: var(--rk-font-family) !important;"
        val familyInherit = if (useOriginalFonts) "" else "font-family: inherit !important;"
        return """
            .rk-chapter {
              font-size: var(--rk-font-size) !important;
              line-height: var(--rk-line-height) !important;
              color: var(--rk-text) !important;
              text-align: var(--rk-text-align) !important;
              $family
            }
            .rk-chapter *:where(:not(.rk-failure, .rk-failure *)) {
              font-size: inherit !important;
              color: inherit !important;
              background-color: transparent !important;
              $familyInherit
            }
            .rk-chapter p {
              text-indent: var(--rk-paragraph-indent) !important;
              margin-bottom: var(--rk-paragraph-spacing) !important;
            }
$sizedElements
        """.trimIndent()
    }

    /** Written from the table the native renderer sizes the same elements by. */
    private val sizedElements: String = buildString {
        NovelTextScale.headings.forEachIndexed { i, size -> appendLine("h${i + 1}" em size) }
        appendLine("sup, .rk-chapter sub" em NovelTextScale.SCRIPT)
        appendLine("small" em NovelTextScale.SMALL)
        append("rt" em NovelTextScale.RUBY_READING)
    }.prependIndent("            ")

    private infix fun String.em(size: Float) = ".rk-chapter $this { font-size: ${size.toString().removeSuffix(
        ".0",
    )}em !important; }"

    /** Safe inside the single quotes the script writes it into, which is all it has to survive. */
    private fun jsString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", " ")
        .replace("\r", " ")

    /** The block the page's own settings object is given, for what a custom property cannot express. */
    fun behaviourJson(settings: NovelReaderSettings): JSONObject = JSONObject().apply {
        put("swipe", settings.swipeGestures)
        put("bionic", settings.bionicReading)
        put("readAloud", readAloudJson(settings))
    }

    /** How the page marks and follows the spoken paragraph. The colours are CSS built from packed ints,
     *  so nothing a preference holds reaches the page's stylesheet as text. */
    private fun readAloudJson(settings: NovelReaderSettings): JSONObject = JSONObject().apply {
        put("highlight", settings.ttsHighlight)
        put("style", settings.ttsHighlightStyle.name)
        put("color", cssRgba(settings.ttsHighlightColor))
        put("textColor", cssRgba(settings.ttsHighlightTextColor))
        put("keepInView", settings.ttsKeepInView)
        put("scrollToTop", settings.ttsScrollToTop)
    }

    private fun cssRgba(argb: Int): String = "rgba(%d,%d,%d,%.3f)".format(
        Locale.ROOT,
        (argb shr 16) and 0xFF,
        (argb shr 8) and 0xFF,
        argb and 0xFF,
        ((argb ushr 24) and 0xFF) / 255f,
    )

    /**
     * The face behind the chosen family, bundled or the user's own. None for a generic family. Its
     * own style block, so a font changed with a chapter open swaps the face without a rebuild.
     */
    fun fontFace(family: String, source: String?): String {
        if (source == null) return ""
        // Dropping the declaration loses the face; letting it through loses the whole style block.
        if (!isSafeInCssUrl(source)) return ""
        return "@font-face { font-family: '${cssFontName(family)}'; src: url('$source'); }"
    }
}
