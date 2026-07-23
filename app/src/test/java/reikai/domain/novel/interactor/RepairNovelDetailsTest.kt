package reikai.domain.novel.interactor

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.Novel

class RepairNovelDetailsTest {

    private fun novel(id: Long, title: String, source: String, url: String, author: String? = "Author") =
        Novel.create().copy(id = id, title = title, source = source, url = url, author = author, favorite = true)

    @Test
    fun `same source, title and author at different urls flags both`() {
        val victim = novel(2, "BTTH", "novelfire", "book/catastrophic-necromancer")
        val donor = novel(1, "BTTH", "novelfire", "book/btth")

        RepairNovelDetails.findSuspects(listOf(donor, victim))
            .map { it.id } shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `the same title on different sources is normal and is not flagged`() {
        val a = novel(1, "BTTH", "novelfire", "book/btth")
        val b = novel(2, "BTTH", "novelarrow", "novel/btth")

        RepairNovelDetails.findSuspects(listOf(a, b)) shouldBe emptyList()
    }

    @Test
    fun `a healthy library flags nothing`() {
        val a = novel(1, "BTTH", "novelfire", "book/btth")
        val b = novel(2, "Catastrophic necromancer", "novelfire", "book/catastrophic-necromancer")

        RepairNovelDetails.findSuspects(listOf(a, b)) shouldBe emptyList()
    }

    @Test
    fun `title matching ignores case and surrounding space`() {
        val a = novel(1, "BTTH", "novelfire", "book/btth")
        val b = novel(2, "  btth ", "novelfire", "book/other")

        RepairNovelDetails.findSuspects(listOf(a, b)).map { it.id } shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `the same title by different authors is not flagged`() {
        // A user-generated source (AO3 and the like) really does host different works under one title.
        val a = novel(1, "Beginning After the End", "archiveofourown", "works/41709720", author = "louwhose")
        val b = novel(2, "Beginning after the End", "archiveofourown", "works/36363133", author = "orphan_account")

        RepairNovelDetails.findSuspects(listOf(a, b)) shouldBe emptyList()
    }

    @Test
    fun `blank titles are never grouped together`() {
        val a = novel(1, "", "novelfire", "book/a")
        val b = novel(2, "", "novelfire", "book/b")

        RepairNovelDetails.findSuspects(listOf(a, b)) shouldBe emptyList()
    }
}
