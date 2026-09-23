package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelChapterNavigationClientTest {

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
