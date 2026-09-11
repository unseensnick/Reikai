package reikai.novel.content

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.jsoup.Jsoup
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * What a chapter's own markup may reach the WebView with. The WebView runs JavaScript with the app's
 * cookie jar and a bridge bound, so a source that gets a script past this executes in that context.
 * The TEXT_VIEW cases are a different question: `Html.fromHtml` executes nothing, so they only pin
 * that the visible-text cleanup still happens.
 */
class NovelHtmlSanitizerTest {

    private fun web(
        content: String,
        keepEmbeddedCss: Boolean = true,
        keepEmbeddedJs: Boolean = false,
        blockMedia: Boolean = false,
    ) = NovelHtmlUtils.sanitizeForRender(
        content = content,
        target = RenderTarget.WEB_VIEW,
        keepEmbeddedCss = keepEmbeddedCss,
        keepEmbeddedJs = keepEmbeddedJs,
        blockMedia = blockMedia,
    )

    /** The regex closed on `</script>` exactly, so one space past it left the whole block standing. */
    @Test
    fun `an end tag with whitespace does not carry a script through`() {
        web("<p>a</p><script>alert(1)</script >") shouldNotContain "alert(1)"
    }

    /**
     * Scripts were stripped before comments, so a comment inside the tag name hid the tag for that
     * pass and the comment strip afterwards put it back together. Asked of the parsed result rather
     * than the string, because that is the question the WebView answers: the fragments left behind
     * are text on the page, and only an element that survives as a `script` can run.
     */
    @Test
    fun `a script spliced with a comment does not survive reassembly`() {
        val spliced = "<p>a</p><scr<!-- -->ipt>alert(1)</scr<!-- -->ipt>"

        Jsoup.parseBodyFragment(web(spliced)).select("script").shouldBeEmpty()
    }

    @Test
    fun `a comment is dropped`() {
        web("<p>a</p><!-- hidden -->") shouldNotContain "hidden"
    }

    /** Serialising foreign content and re-parsing it does not always round-trip, which is the shape
     *  every mutation bypass takes. A chapter has no use for either, so neither reaches the page. */
    @Test
    fun `foreign content is removed`() {
        web("<svg><desc><p>a</p></desc></svg>") shouldNotContain "svg"
    }

    @Test
    fun `an event handler attribute is removed`() {
        web("""<img src="x" onerror="alert(1)">""") shouldNotContain "onerror"
    }

    @Test
    fun `a javascript url is removed`() {
        web("""<a href="javascript:alert(1)">tap</a>""") shouldNotContain "javascript:"
    }

    /** A browser skips whitespace and control characters when it reads the scheme, so each of these
     *  still runs as code. One per clause of the normalisation. */
    @ParameterizedTest
    @ValueSource(strings = ["\tjava\nscript:alert(1)", " javascript:alert(1)", "\u0001javascript:alert(1)"])
    fun `a padded javascript url is removed`(url: String) {
        Jsoup.parseBodyFragment(web("""<a href="$url">tap</a>""")).select("a[href]").shouldBeEmpty()
    }

    /**
     * An escaped comment in one text node and its close inside a later raw-text element: cutting the
     * span out of the serialised markup took the element's start tag with it, and what it held as text
     * came back as a live element that the attribute pass never saw.
     */
    @Test
    fun `an escaped comment cannot reach into a style block and free what it holds`() {
        val spanning = "<p>&lt;!--</p><style>--&gt;<img src=x onerror=alert(1)></style>"

        Jsoup.parseBodyFragment(web(spanning)).select("[onerror]").shouldBeEmpty()
    }

    /** Raw-text content is written out as it stands, so cutting a comment out of it can assemble an
     *  end tag and let what follows out. */
    @Test
    fun `a comment inside a style block is left alone`() {
        val spliced = "<style><<!--x-->/style><img src=x onerror=alert(1)></style>"

        Jsoup.parseBodyFragment(web(spliced)).select("[onerror]").shouldBeEmpty()
    }

    /** Escaped by the source, so text to the parser, and it would otherwise show mid-paragraph. */
    @Test
    fun `an escaped comment inside a paragraph is dropped`() {
        web("<p>a &lt;!-- hidden --&gt; b</p>") shouldNotContain "hidden"
    }

    /** `plaintext` has no end tag, so everything after it in the page becomes text, the reader's own
     *  engine script included. */
    @Test
    fun `a plaintext element does not swallow the page after the chapter`() {
        val page = Jsoup.parse("<div>${web("<p>one</p><plaintext>two")}</div><script>engine()</script>")

        page.select("script") shouldHaveSize 1
    }

    @Test
    fun `the text a plaintext element holds still shows`() {
        web("<p>one</p><plaintext>two") shouldContain "two"
    }

    /** An `xmp` block shows its content as literal text, which it keeps unescaped in the markup, where
     *  any later pass over the page's markup can turn it back into elements. */
    @Test
    fun `the content of an xmp block reaches the page escaped`() {
        web("<xmp><b>x</b></xmp>") shouldContain "&lt;b&gt;"
    }

    /** Fallbacks for features the reader never has, whose raw-text content no browser shows. */
    @ParameterizedTest
    @ValueSource(strings = ["noembed", "noframes"])
    fun `a fallback for embedded content is removed`(tag: String) {
        web("<$tag><img src=x onerror=alert(1)></$tag>") shouldNotContain "onerror"
    }

    /** No regex covered these at all, and both run their own content in the page. */
    @Test
    fun `a frame is removed`() {
        web("""<iframe src="https://example.invalid"></iframe>""") shouldNotContain "iframe"
    }

    @Test
    fun `a plugin element is removed`() {
        web("""<object data="x.swf"></object>""") shouldNotContain "object"
    }

    /** Keeping the source's CSS is a setting and defaults on, so hardening must not quietly drop it. */
    @Test
    fun `the source's own styles are kept when the setting is on`() {
        val kept = web("<style>p { color: red }</style><p>body text</p>")

        kept shouldContain "color: red"
        kept shouldContain "body text"
    }

    @Test
    fun `the source's own styles are dropped when the setting is off`() {
        web("<style>p { color: red }</style><p>a</p>", keepEmbeddedCss = false) shouldNotContain "color: red"
    }

    /** A browser reads `rel` as a list of words, so any list holding stylesheet loads one. */
    @Test
    fun `a stylesheet link is dropped whatever else its rel lists`() {
        val link = """<link rel="stylesheet preload" href="https://x.invalid/a.css"><p>a</p>"""

        web(link, keepEmbeddedCss = false) shouldNotContain "a.css"
    }

    /** A style attribute is CSS, so it follows the same setting rather than the script rule. */
    @Test
    fun `a style attribute is dropped when the setting is off`() {
        web("""<p style="color: red">a</p>""", keepEmbeddedCss = false) shouldNotContain "color: red"
    }

    @Test
    fun `a script is kept when the reader was told to keep them`() {
        web("<script>alert(1)</script>", keepEmbeddedJs = true) shouldContain "alert(1)"
    }

    @Test
    fun `blocking media removes an image`() {
        web("""<p>a</p><img src="x.png">""", blockMedia = true) shouldNotContain "img"
    }

    @Test
    fun `paragraph text survives`() {
        web("<p>the chapter</p>") shouldContain "the chapter"
    }

    /** The line breaks a plain-text chapter is rebuilt with, which a reflow would collapse. */
    @Test
    fun `a run of breaks survives`() {
        web("<p>one<br><br>two</p>") shouldContain "<br><br>"
    }

    /** The TextView's pattern cleanup leaves attributes alone, so it is no fallback for a page that
     *  runs scripts. */
    @Test
    fun `a chapter the parser cannot read reaches the page as text`() {
        mockkStatic(Jsoup::class)
        every { Jsoup.parseBodyFragment(any()) } throws IllegalStateException("unreadable")
        val sanitized = try {
            web("""<img src="x" onerror="alert(1)">""")
        } finally {
            unmockkStatic(Jsoup::class)
        }

        sanitized shouldNotContain "<img"
    }

    @Test
    fun `the text target still strips a script`() {
        val stripped = NovelHtmlUtils.sanitizeForRender(
            content = "<p>a</p><script>alert(1)</script>",
            target = RenderTarget.TEXT_VIEW,
            keepEmbeddedCss = true,
            keepEmbeddedJs = true,
            blockMedia = false,
        )

        stripped shouldNotContain "alert(1)"
    }
}
