package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.presentation.reader.NovelChapterNavigationClient.Companion.decide
import reikai.presentation.reader.NovelChapterNavigationClient.Decision

/**
 * The reader's WebView holds the native bridge and the app's cookie jar, so what a chapter's markup
 * is allowed to navigate to is a security rule, not a UX one. Chapter markup is source-controlled.
 */
class NovelChapterNavigationClientTest {

    private val base = "https://source.example/novel/ch1"

    /** An empty `href` resolves to the document's own URL, and following it loads the live page from
     *  the source's site into the reader. */
    @Test
    fun `a tapped link to the document's own url is refused`() {
        decide(base, base, hasGesture = true) shouldBe Decision.BLOCK
    }

    @Test
    fun `the page reloading itself is refused`() {
        decide(base, base, hasGesture = false) shouldBe Decision.BLOCK
    }

    @Test
    fun `a footnote jump within the chapter is allowed`() {
        decide("$base#note-4", base, hasGesture = true) shouldBe Decision.ALLOW
    }

    @Test
    fun `a tapped link to another site opens outside the reader`() {
        decide("https://elsewhere.example/", base, hasGesture = true) shouldBe Decision.OPEN_EXTERNALLY
    }

    @Test
    fun `a navigation the page starts by itself is refused`() {
        decide("https://elsewhere.example/", base, hasGesture = false) shouldBe Decision.BLOCK
    }

    @Test
    fun `an intent url is refused even when tapped`() {
        decide("intent://evil#Intent;scheme=http;end", base, hasGesture = true) shouldBe Decision.BLOCK
    }

    @Test
    fun `a javascript url is refused even when tapped`() {
        decide("javascript:alert(1)", base, hasGesture = true) shouldBe Decision.BLOCK
    }

    @Test
    fun `a file url is refused even when tapped`() {
        decide("file:///data/data/app.reikai/databases/", base, hasGesture = true) shouldBe Decision.BLOCK
    }

    /** A prefix match alone would let this through, since it starts with the document's own URL. */
    @Test
    fun `a sibling path sharing the document's url as a prefix is not the document`() {
        decide("$base-evil", base, hasGesture = false) shouldBe Decision.BLOCK
    }

    /** Without the null check a missing base renders as "null", so this would read as the document. */
    @Test
    fun `nothing is same-document when the chapter was loaded without an origin`() {
        decide("null#note", null, hasGesture = true) shouldBe Decision.BLOCK
    }

    @Test
    fun `a cached SVG is served as SVG, which Chromium never sniffs`() {
        imageResponseType(
            null,
            """<?xml version="1.0"?><svg xmlns="http://www.w3.org/2000/svg"/>""".toByteArray(),
        ) shouldBe
            "image/svg+xml"
    }

    @Test
    fun `a cached raster picture is left for Chromium to sniff`() {
        imageResponseType(null, byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)) shouldBe "image/*"
    }

    @Test
    fun `a type the response named is kept`() {
        imageResponseType("image/webp", byteArrayOf()) shouldBe "image/webp"
    }
}
