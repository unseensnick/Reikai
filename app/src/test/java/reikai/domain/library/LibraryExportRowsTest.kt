package reikai.domain.library

import eu.kanade.tachiyomi.data.export.LibraryExporter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.merge.TestMergeManagers
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.Manga

class LibraryExportRowsTest {

    private val allColumns = LibraryExporter.ExportOptions(
        includeTitle = true,
        includeAuthor = true,
        includeArtist = true,
    )

    private fun manga(id: Long, title: String) = Manga.create().copy(id = id, title = title, author = "A")

    private fun novel(id: Long, title: String) = Novel.create().copy(id = id, title = title, author = "B")

    @Test
    fun `the library list carries a novel beside a manga`() {
        val rows = libraryExportRows(manga = listOf(manga(1, "Manga")), novels = listOf(novel(1, "Novel")))

        LibraryExporter.generateCsvData(rows, allColumns) shouldBe "Manga,A,\r\nNovel,B,"
    }

    @Test
    fun `a merged series is written once, as its lowest id member`() = runTest {
        exportOf(mergingOn = true).map { it.title } shouldBe listOf("First source", "Novel one")
    }

    /** With series merging off the library shows one card per source, so the export writes each. */
    @Test
    fun `with series merging off every source of a merged series is its own row`() = runTest {
        exportOf(mergingOn = false).size shouldBe 4
    }

    private suspend fun exportOf(mergingOn: Boolean): List<LibraryExportRow> {
        val managers = TestMergeManagers(
            memberships = mapOf(
                ContentType.MANGA to mapOf(1L to 10L, 2L to 10L),
                ContentType.NOVELS to mapOf(1L to 20L, 2L to 20L),
            ),
            mergingOn = mergingOn,
        )
        return GetLibraryExportRows(
            getFavorites = mockk<GetFavorites> {
                coEvery { await() } returns listOf(manga(2, "Second source"), manga(1, "First source"))
            },
            novelRepository = mockk<NovelRepository> {
                coEvery { getFavorites() } returns listOf(novel(2, "Novel two"), novel(1, "Novel one"))
            },
            mangaMergeManager = managers.manga,
            novelMergeManager = managers.novel,
        ).await()
    }
}
