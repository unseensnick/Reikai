package reikai.presentation.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelSourceBadgeTest {

    @Test
    fun `a novel whose source is not installed draws the missing-source badge`() {
        novelSourceBadge(null) shouldBe NovelSourceBadge.Missing
    }
}
