package exh.md

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class MangaDexSyncDetailTest {

    @Test
    fun `a section lists the series the notification may name`() {
        syncDetail("1 added, 1 failed", listOf("Failed:" to listOf("A Series"))) shouldBe
            "1 added, 1 failed\n\nFailed:\nA Series"
    }

    @Test
    fun `a series the notification may not name is left to the count`() {
        syncDetail("1 added, 2 failed", listOf("Failed:" to listOf(null, "A Series"))) shouldBe
            "1 added, 2 failed\n\nFailed:\nA Series"
    }

    @Test
    fun `a section of only unnamed series is left out`() {
        syncDetail("0 added, 1 skipped", listOf("Skipped:" to listOf(null))) shouldBe "0 added, 1 skipped"
    }
}
