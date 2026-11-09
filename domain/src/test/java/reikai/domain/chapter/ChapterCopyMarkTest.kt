package reikai.domain.chapter

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A copy mark survives the backup field both types carry it in. */
class ChapterCopyMarkTest {

    private data class Row(val url: String, val value: Long?)

    @Test
    fun `each mark comes back from its backup value, and an unmarked chapter carries none`() {
        val marks = mapOf("/a" to CopyMark.NeverSkip, "/b" to CopyMark.Group(1_700_000_000_000L))
        val rows =
            listOf(
                Row("/a", marks.getValue("/a").toBackupValue()),
                Row("/b", marks.getValue("/b").toBackupValue()),
                Row("/c", null),
            )

        backedUpCopyMarks(rows, { it.url }, { it.value }) shouldBe marks
    }
}
