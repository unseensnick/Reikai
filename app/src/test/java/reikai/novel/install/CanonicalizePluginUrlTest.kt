package reikai.novel.install

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanonicalizePluginUrlTest {

    @Test
    fun `literal brackets canonicalize to percent encoded`() {
        assertEquals(
            "https://example.com/path/NovelBin%5Breadnovelfull%5D.js",
            canonicalizePluginUrl("https://example.com/path/NovelBin[readnovelfull].js"),
        )
    }

    @Test
    fun `already encoded URL stays encoded`() {
        val encoded = "https://example.com/path/NovelBin%5Breadnovelfull%5D.js"
        assertEquals(encoded, canonicalizePluginUrl(encoded))
    }

    @Test
    fun `both bracket forms produce the same canonical`() {
        val literal = canonicalizePluginUrl("https://example.com/path/NovelBin[readnovelfull].js")
        val encoded = canonicalizePluginUrl("https://example.com/path/NovelBin%5Breadnovelfull%5D.js")
        assertEquals(literal, encoded)
    }

    @Test
    fun `URL without brackets is unchanged`() {
        val clean = "https://example.com/path/royalroad.js"
        assertEquals(clean, canonicalizePluginUrl(clean))
    }
}
