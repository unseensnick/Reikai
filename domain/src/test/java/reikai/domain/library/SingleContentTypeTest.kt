package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The library's Merge and the migration entry point both act only on a selection of one type. */
class SingleContentTypeTest {

    @Test
    fun `only a non-empty list of one type has a single type`() {
        listOf(
            emptyList(),
            listOf(ContentType.MANGA, ContentType.MANGA),
            listOf(ContentType.MANGA, ContentType.NOVELS),
        ).map(::singleContentType) shouldBe listOf(null, ContentType.MANGA, null)
    }
}
