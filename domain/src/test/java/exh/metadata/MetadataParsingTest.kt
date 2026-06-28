package exh.metadata

import exh.metadata.metadata.EHentaiSearchMetadata
import exh.metadata.metadata.NHentaiSearchMetadata
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Pure parsing helpers behind the gallery sources: byte-size strings (used for the size badge) and
 * the id/token extraction the EH/NH metadata pipeline relies on. The full-URL branch of the EH
 * parser goes through android.net.Uri, so these cover only the path-only branch (pure JVM).
 */
class MetadataParsingTest {

    @Test
    fun `parses gibibytes against the 1024 base`() {
        MetadataUtil.parseHumanReadableByteCount("1.0 GiB") shouldBe 1073741824.0
    }

    @Test
    fun `parses gigabytes against the 1000 base`() {
        MetadataUtil.parseHumanReadableByteCount("1.0 GB") shouldBe 1.0E9
    }

    @Test
    fun `returns null for an unrecognized unit`() {
        MetadataUtil.parseHumanReadableByteCount("1.0 XB") shouldBe null
    }

    @Test
    fun `throws when the size has no numeric prefix`() {
        shouldThrow<NumberFormatException> { MetadataUtil.parseHumanReadableByteCount("bad") }
    }

    @Test
    fun `reads the gallery id from a path url`() {
        EHentaiSearchMetadata.galleryId("/g/123/abc/") shouldBe "123"
    }

    @Test
    fun `reads the gallery token from a path url`() {
        EHentaiSearchMetadata.galleryToken("/g/123/abc/") shouldBe "abc"
    }

    @Test
    fun `normalizes a path url to the canonical id and token form`() {
        EHentaiSearchMetadata.normalizeUrl("/g/123/abc/") shouldBe "/g/123/abc/?nw=always"
    }

    @Test
    fun `reads the nhentai id from a trailing-slash url`() {
        NHentaiSearchMetadata.nhUrlToId("/g/456/") shouldBe 456L
    }

    @Test
    fun `round-trips an nhentai id through path and back`() {
        NHentaiSearchMetadata.nhUrlToId(NHentaiSearchMetadata.nhIdToPath(789)) shouldBe 789L
    }
}
