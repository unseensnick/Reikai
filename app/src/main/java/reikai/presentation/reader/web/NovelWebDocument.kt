package reikai.presentation.reader.web

import android.content.Context
import org.json.JSONObject
import reikai.novel.font.fontDisplayName
import reikai.novel.font.isSupportedFontFile
import reikai.presentation.novel.reader.NovelReaderSettings
import reikai.presentation.novel.reader.cssBackgroundColor
import reikai.presentation.novel.reader.cssFontFamily
import reikai.presentation.novel.reader.cssTextAlign
import reikai.presentation.novel.reader.cssTextColor
import reikai.presentation.novel.reader.isSafeInCssUrl
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * The document the WebView rendering mode renders, assembled here because it is this renderer's own
 * format. Its stylesheet and engine are inlined from `assets/novel-web/`.
 *
 * Every settings-derived value goes through the sanitisers in `NovelReaderCssValues`, since a
 * restored backup can put any string in a preference and this block runs in a page holding the
 * app's cookies.
 */
object NovelWebDocument {

    /** Where a chapter is considered finished, matching what the page reports as one. */
    private const val DONE_THRESHOLD = 0.99

    fun build(
        context: Context,
        chapterId: Long,
        chapterTitle: String,
        chapterHtml: String,
        initialFraction: Float,
        settings: NovelReaderSettings,
        statusBarHeightPx: Int,
        customFontUrl: String?,
        useOriginalFonts: Boolean,
        sourceCssPriority: Boolean,
    ): String {
        val css = NovelWebAssets.read(context, "reader.css")
        val js = NovelWebAssets.readWith(
            context,
            "reader.js",
            mapOf(
                "__TAP_TO_SCROLL__" to settings.tapToScroll.toString(),
                "__SWIPE__" to settings.swipeGestures.toString(),
                "__BIONIC__" to settings.bionicReading.toString(),
                "__DONE_THRESHOLD__" to DONE_THRESHOLD.toString(),
                "__INITIAL_FRACTION__" to initialFraction.coerceIn(0f, 1f).toString(),
                // The seam names both chapters under these, the way TransitionText does. Resolved
                // here because the page has no resources of its own.
                "__LABEL_FINISHED__" to jsString(context.stringResource(MR.strings.transition_finished)),
                "__LABEL_NEXT__" to jsString(context.stringResource(MR.strings.transition_next)),
            ),
        )
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1">
            <style>
            ${fontFace(settings.fontFamily, customFontUrl)}
            :root { ${variables(settings, statusBarHeightPx)} }
            $css
            ${overrides(useOriginalFonts, sourceCssPriority)}
            </style>
            </head>
            <body>
            <div id="rk-chapters">
            <div class="rk-chapter" data-rk-chapter-id="$chapterId"
                 data-rk-chapter-title="${attribute(chapterTitle)}">$chapterHtml</div>
            </div>
            <script>$js</script>
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
        append("--rk-font-family:").append(webFontFamily(settings.fontFamily).ifEmpty { "serif" }).append(';')
        append("--rk-margin-top:").append(settings.margins.top).append("px;")
        append("--rk-margin-bottom:").append(settings.margins.bottom).append("px;")
        append("--rk-margin-left:").append(settings.margins.left).append("px;")
        append("--rk-margin-right:").append(settings.margins.right).append("px;")
        append("--rk-paragraph-indent:").append(settings.paragraphIndent).append("em;")
        append("--rk-paragraph-spacing:").append(settings.paragraphSpacing).append("em;")
        append("--rk-inset-top:").append(statusBarHeightPx).append("px;")
    }

    /**
     * How the reader's display settings hold their ground against a chapter that ships its own CSS.
     * A chapter's styles sit inside the body and so win a tie on document order, which is why these
     * carry `!important` rather than being folded into the stylesheet. Ported from tsundoku's
     * `fontOverrideCss`, including why the headings are restated: forcing `font-size: inherit` on
     * every element is what stops a source sizing its own text, and it flattens headings with it.
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
        """.trimIndent()
    }

    /** Safe inside the single quotes the script writes it into, which is all it has to survive. */
    private fun jsString(value: String): String = value
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace("\n", " ")
        .replace("\r", " ")

    /**
     * A chapter's title is carried on its element so a seam can name both chapters it sits between.
     * An insert reads the neighbour's back off the DOM, since one of the two titles it needs belongs
     * to the chapter already there rather than the arriving one.
     */
    private fun attribute(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /** The block the page's own settings object is given, for what a custom property cannot express. */
    fun behaviourJson(settings: NovelReaderSettings): JSONObject = JSONObject().apply {
        put("tapToScroll", settings.tapToScroll)
        put("swipe", settings.swipeGestures)
        put("bionic", settings.bionicReading)
    }

    /**
     * The `@font-face` a font the user added needs, since it lives under their storage location
     * rather than in the assets folder the bundled faces come from.
     */
    private fun fontFace(family: String, url: String?): String {
        if (url == null || !isSupportedFontFile(family)) return ""
        // Dropping the declaration loses the face; letting it through loses the whole style block.
        if (!isSafeInCssUrl(url)) return ""
        return "@font-face { font-family: '${webFontFamily(family)}'; src: url('$url'); }"
    }

    /**
     * What the page is told the family is called. A font the user added is stored as its file name,
     * and `font-family: Merriweather.ttf` is not a valid family, so the face would be declared and
     * never referenced. The readable name has no dot in it.
     */
    private fun webFontFamily(family: String): String =
        cssFontFamily(if (isSupportedFontFile(family)) fontDisplayName(family) else family)
}
