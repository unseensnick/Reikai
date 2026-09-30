package reikai.domain.merge

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/** The merged reading order both providers render, pinned once for both content types. */
class RenderMergedReadingOrderTest {

    private data class Row(val id: Long, val sourceOrder: Long)

    private fun render(rows: List<Row>, stitch: List<ChapterUnit>) =
        renderMergedReadingOrder(rows, stitch, { it.id }) { row, order -> row.copy(sourceOrder = order) }

    @Test
    @DisplayName("an ungrouped list keeps its own rows and source order")
    fun emptyStitchKeepsTheList() {
        val rows = listOf(Row(id = 3, sourceOrder = 7), Row(id = 1, sourceOrder = 9))

        render(rows, emptyList()) shouldBe rows
    }

    @Test
    @DisplayName("a grouped list runs in the stitch's order with its positions as source order")
    fun stitchRestampsSourceOrder() {
        val rows = listOf(Row(id = 20, sourceOrder = 5), Row(id = 10, sourceOrder = 8), Row(id = 21, sourceOrder = 4))
        val stitch = listOf(
            ChapterUnit(chapterId = 10, unit = 0, copyOrder = 0),
            ChapterUnit(chapterId = 20, unit = 0, copyOrder = 1),
            ChapterUnit(chapterId = 21, unit = 1, copyOrder = 0),
        )

        render(rows, stitch) shouldBe listOf(Row(id = 10, sourceOrder = 0), Row(id = 21, sourceOrder = 1))
    }
}
