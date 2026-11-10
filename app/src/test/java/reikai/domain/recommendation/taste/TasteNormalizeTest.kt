package reikai.domain.recommendation.taste

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TasteNormalizeTest {

    @Test
    fun `two spellings the tag filter treats as one are one taste key`() {
        listOf("Slice-of-Life", "slice of life").toTagKeys() shouldBe listOf("slice of life")
    }
}
