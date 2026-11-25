package reikai.domain.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** A drop re-slots the tiles shown into the places they held, leaving the hidden ones where they were. */
class ReorderedPositionsTest {

    private fun manga(id: Long, position: Long) = ManualOrderPosition(ContentType.MANGA, id, 0, position)
    private fun novel(id: Long, position: Long) = ManualOrderPosition(ContentType.NOVELS, id, 0, position)
    private fun tile(type: ContentType, vararg ids: Long) = ids.mapTo(HashSet()) { type to it }

    private fun List<ManualOrderPosition>.order() =
        sortedBy { it.position }.map { "${if (it.contentType == ContentType.MANGA) "m" else "n"}${it.entryId}" }

    @Test
    fun `a first drop numbers the tiles in their new order`() {
        val result = reorderedPositions(0, emptyList(), listOf(tile(ContentType.MANGA, 2), tile(ContentType.MANGA, 1)))

        result.order() shouldBe listOf("m2", "m1")
    }

    /** Under the Novels chip the manga are hidden, so they keep the places between the novels. */
    @Test
    fun `hidden tiles keep their places while the shown ones swap`() {
        val stored = listOf(novel(1, 0), manga(9, 1), novel(2, 2))

        val result = reorderedPositions(0, stored, listOf(tile(ContentType.NOVELS, 2), tile(ContentType.NOVELS, 1)))

        result.order() shouldBe listOf("n2", "m9", "n1")
    }

    @Test
    fun `a shown tile with no place yet follows the rest`() {
        val stored = listOf(novel(1, 0), manga(9, 1))

        val result = reorderedPositions(0, stored, listOf(tile(ContentType.NOVELS, 3), tile(ContentType.NOVELS, 1)))

        result.order() shouldBe listOf("n3", "m9", "n1")
    }

    @Test
    fun `every member of a merged tile takes its place`() {
        val result =
            reorderedPositions(0, emptyList(), listOf(tile(ContentType.MANGA, 1), tile(ContentType.MANGA, 5, 6)))

        result.filter { it.entryId != 1L }.map { it.position }.toSet() shouldBe setOf(1L)
    }
}
