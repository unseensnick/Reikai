package reikai.domain.novel

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The stored alignment strings are the preference's whole vocabulary, and a restored backup can write
 * anything under its key, which the web document then puts inside a style block.
 */
class NovelTextAlignTest {

    @ParameterizedTest
    @EnumSource(NovelTextAlign::class)
    fun `each alignment reads back from the string it is stored as`(align: NovelTextAlign) {
        NovelTextAlign.of(align.value) shouldBe align
    }

    @Test
    fun `a stored value that is not an alignment reads as left`() {
        NovelTextAlign.of("left; } </style><script>alert(1)</script>") shouldBe NovelTextAlign.LEFT
    }
}
