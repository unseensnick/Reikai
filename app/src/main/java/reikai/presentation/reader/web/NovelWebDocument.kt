package reikai.presentation.reader.web

import android.content.Context
import com.google.android.material.color.MaterialColors
import org.json.JSONObject
import reikai.presentation.novel.reader.NovelReaderSettings
import reikai.presentation.novel.reader.cssBackgroundColor
import reikai.presentation.novel.reader.cssFontFamilyValue
import reikai.presentation.novel.reader.cssFontName
import reikai.presentation.novel.reader.cssTextAlign
import reikai.presentation.novel.reader.cssTextColor
import reikai.presentation.novel.reader.isSafeInCssUrl
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
                "__TAP_TO_SCROLL__" to settings.tapToScroll.toString(),
                "__SWIPE__" to settings.swipeGestures.toString(),
                "__BIONIC__" to settings.bionicReading.toString(),
                "__INITIAL_FRACTION__" to initialFraction.coerceIn(0f, 1f).toString(),
                "__DOCUMENT_TOKEN__" to jsString(documentToken),
                // The seam names both chapters under these, the way TransitionText does. Resolved
                // here because the page has no resources of its own.
                "__LABEL_FINISHED__" to jsString(context.stringResource(MR.strings.transition_finished)),
                "__LABEL_NEXT__" to jsString(context.stringResource(MR.strings.transition_next)),
                "__LABEL_DOWNLOADED__" to jsString(context.stringResource(MR.strings.label_downloaded)),
            ),
        )
        // The engine is in the head so it runs before the chapter: it holds the bridge and its token
        // before any script the chapter carries can reach them, and a chapter whose markup never
        // closes (a stray `<plaintext>`) cannot swallow the engine as text.
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <style id="rk-font-face">${fontFace(settings.fontFamily, fontSource)}</style>
            <style>
            :root { ${variables(settings, statusBarHeightPx)} ${chromeVariables(context)} }
            $css
            ${overrides(useOriginalFonts, sourceCssPriority)}
            ${if (textSelectable) "" else "body { -webkit-user-select: none; user-select: none; }"}
            </style>
            <script>$js</script>
            </head>
            <body>
            <div id="rk-chapters">
            <div class="rk-chapter" data-rk-chapter-id="$chapterId">$chapterHtml</div>
            </div>
            </body>
            </html>
        """.trimIndent()
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
        append("--rk-font-family:").append(cssFontFamilyValue(settings.fontFamily).ifEmpty { "serif" }).append(';')
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
     * seam view), written once; the error colour is the app theme's, as the text renderer's failure
     * and gap warning draw in it.
     */
    private fun chromeVariables(context: Context): String = buildString {
        append("--rk-seam-padding-vertical:").append(NovelChapterSeamView.PADDING_VERTICAL_DP).append("px;")
        append("--rk-seam-padding-horizontal:").append(NovelChapterSeamView.PADDING_HORIZONTAL_DP).append("px;")
        val error = MaterialColors.getColor(context, com.google.android.material.R.attr.colorError, FALLBACK_ERROR)
        append("--rk-error:").append("#%06X".format(Locale.ROOT, error and 0xFFFFFF)).append(';')
    }

    /** Material's baseline error red, for a context whose theme names none. */
    private const val FALLBACK_ERROR = 0xFFB3261E.toInt()

    /**
     * How the reader's display settings hold their ground against a chapter that ships its own CSS.
     * A chapter's styles sit inside the body and so win a tie on document order, which is why these
     * carry `!important` rather than being folded into the stylesheet. Ported from tsundoku's
     * `fontOverrideCss`, including why the headings are restated: forcing `font-size: inherit` on
     * every element is what stops a source sizing its own text, and it flattens headings with it.
     * Tsundoku restates only headings, which left footnote markers at full body size.
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
            .rk-chapter * {
              font-size: inherit !important;
              color: inherit !important;
              background-color: transparent !important;
              $familyInherit
            }
            .rk-chapter p {
              text-indent: var(--rk-paragraph-indent) !important;
              margin-bottom: var(--rk-paragraph-spacing) !important;
            }
            .rk-chapter h1 { font-size: 2em !important; }
            .rk-chapter h2 { font-size: 1.5em !important; }
            .rk-chapter h3 { font-size: 1.17em !important; }
            .rk-chapter h4 { font-size: 1em !important; }
            .rk-chapter h5 { font-size: 0.83em !important; }
            .rk-chapter h6 { font-size: 0.67em !important; }
            .rk-chapter sup, .rk-chapter sub { font-size: 0.7em !important; }
            .rk-chapter small { font-size: 0.83em !important; }
        """.trimIndent()
    }

    /** Safe inside the single quotes the script writes it into, which is all it has to survive. */
    private fun jsString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", " ")
        .replace("\r", " ")

    /** The block the page's own settings object is given, for what a custom property cannot express. */
    fun behaviourJson(settings: NovelReaderSettings): JSONObject = JSONObject().apply {
        put("tapToScroll", settings.tapToScroll)
        put("swipe", settings.swipeGestures)
        put("bionic", settings.bionicReading)
    }

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
