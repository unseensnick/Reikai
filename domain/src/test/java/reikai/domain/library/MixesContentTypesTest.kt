package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The download queue and the Repos screen badge a row's type by this one rule. */
class MixesContentTypesTest {

    @Test
    fun `only a list holding both types mixes them`() {
        listOf(
            emptyList(),
            listOf(ContentType.MANGA, ContentType.MANGA),
            listOf(ContentType.MANGA, ContentType.NOVELS),
        ).map(::mixesContentTypes) shouldBe listOf(false, false, true)
    }
}
