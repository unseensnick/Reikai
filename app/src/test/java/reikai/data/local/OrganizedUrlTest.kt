package reikai.data.local

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
import reikai.domain.local.LocalFileJournalPlan.UrlRule
import reikai.domain.local.VolumeOwner
import reikai.novel.local.LocalNovelSource
import reikai.novel.local.epub.EpubChapterKey

/** Where Organize files takes each chapter address, both content types, and that its Undo takes it back. */
class OrganizedUrlTest {

    private val mangaRules = listOf(
        UrlRule("B", "Vol 1", "Saga", "Saga - Vol. 2"),
        UrlRule("C", VolumeOwner.LOOSE, "Saga", "Saga - Vol. 3", "Ch 9.cbz"),
    )

    private fun manga(url: String, rules: List<UrlRule> = mangaRules) =
        organizedUrl(ContentType.MANGA, url, rules, "A", "Saga")

    @Test
    fun `a moved manga volume, its Extras and another folder's loose chapters take their new places`() {
        listOf(manga("B/Vol 1/Ch 1"), manga("B/Vol 1/"), manga("C/Ch 9.cbz")) shouldBe
            listOf("Saga/Saga - Vol. 2/Ch 1", "Saga/Saga - Vol. 2/", "Saga/Saga - Vol. 3/Ch 9.cbz")
    }

    /** Found by the randomized test: a chapter every listing hid did not move, so its address must not either. */
    @Test
    fun `a loose chapter of another folder that no rule names keeps its address`() {
        manga("C/Ch 10.cbz") shouldBe "C/Ch 10.cbz"
    }

    @Test
    fun `a volume of the series' own folder follows the folder's new name, and another folder's stays`() {
        listOf(manga("A/Vol 1/Ch 1"), manga("A/Ch 2"), manga("D/Vol 1/Ch 1")) shouldBe
            listOf("Saga/Vol 1/Ch 1", "Saga/Ch 2", "D/Vol 1/Ch 1")
    }

    @Test
    fun `the reversed rules take a moved manga address back where it was`() {
        val back = mangaRules.map { it.reversed() }

        organizedUrl(ContentType.MANGA, "Saga/Saga - Vol. 3/Ch 9.cbz", back, "Saga", "A") shouldBe "C/Ch 9.cbz"
    }

    @Test
    fun `a book takes its new folder and name and keeps its chapter key`() {
        val key = EpubChapterKey("ch1.xhtml", fragment = "p2")
        val url = LocalNovelSource.chapterPath("B", "b.epub", key)

        organizedUrl(
            ContentType.NOVELS,
            url,
            listOf(UrlRule("B", "b.epub", "Saga", "Saga - 2.epub")),
            "A",
            "Saga",
        ) shouldBe
            LocalNovelSource.chapterPath("Saga", "Saga - 2.epub", key)
    }
}
