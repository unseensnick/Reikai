package exh.metadata

import exh.metadata.metadata.NHentaiSearchMetadata
import exh.metadata.metadata.base.RaisedTag
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * Guards the gallery-metadata persistence path: a metadata object flattened for storage
 * (JSON extra + tag/title rows) must restore identically when raised back. This is the
 * correctness the search_metadata / search_tags / search_titles store depends on.
 */
class RaisedSearchMetadataFlattenTest {

    @Test
    fun `flatten then raise round-trips fields, tags and titles`() {
        val original = NHentaiSearchMetadata().apply {
            mangaId = 42L
            uploader = "someUploader"
            nhId = 12345L
            mediaId = "67890"
            englishTitle = "An English Title"
            shortTitle = "Short"
            tags += RaisedTag("artist", "someone", NHentaiSearchMetadata.TAG_TYPE_DEFAULT)
            tags += RaisedTag("tag", "vanilla", NHentaiSearchMetadata.TAG_TYPE_DEFAULT)
        }

        val restored = original.flatten().raise(NHentaiSearchMetadata::class)

        // Source-specific fields survive the JSON extra round-trip.
        restored.mangaId shouldBe 42L
        restored.uploader shouldBe "someUploader"
        restored.nhId shouldBe 12345L
        restored.mediaId shouldBe "67890"
        // Titles survive via the flattened title rows (delegate-backed).
        restored.englishTitle shouldBe "An English Title"
        restored.shortTitle shouldBe "Short"
        // Tags survive via the flattened tag rows.
        restored.tags.map { it.namespace to it.name } shouldBe listOf(
            "artist" to "someone",
            "tag" to "vanilla",
        )
    }
}
