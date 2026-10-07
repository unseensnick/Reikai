package reikai.domain.library

import eu.kanade.tachiyomi.data.export.LibraryExporter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryCustomInfo
import reikai.domain.entry.EntryId
import reikai.domain.merge.TestMergeManagers
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import tachiyomi.domain.manga.interactor.GetFavorites
import tachiyomi.domain.manga.model.CustomMangaInfo
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
        val rows = libraryExportRows(
            manga = listOf(manga(1, "Manga")),
            novels = listOf(novel(1, "Novel")),
            customInfo = emptyMap(),
        )

        LibraryExporter.generateCsvData(rows, allColumns) shouldBe "Manga,A,\r\nNovel,B,"
    }

    /** Ids overlap across the two types, so each override reaches only its own type's row. */
    @Test
    fun `each entry is written under its own Edit info overrides`() {
        val rows = libraryExportRows(
            manga = listOf(manga(1, "Manga")),
            novels = listOf(novel(1, "Novel")),
            customInfo = mapOf(
                EntryId.Manga(1) to CustomMangaInfo(mangaId = 1, title = "My manga"),
                EntryId.Novel(1) to CustomNovelInfo(novelId = 1, author = "Me"),
            ),
        )

        LibraryExporter.generateCsvData(rows, allColumns) shouldBe "My manga,A,\r\nNovel,Me,"
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

    @Test
    fun `the export reads every Edit info override`() = runTest {
        val custom = mapOf<EntryId, EntryCustomInfo>(EntryId.Novel(1) to CustomNovelInfo(novelId = 1, title = "Mine"))

        exportOf(mergingOn = true, customInfo = custom).map { it.title } shouldBe listOf("First source", "Mine")
    }

    private suspend fun exportOf(
        mergingOn: Boolean,
        customInfo: Map<EntryId, EntryCustomInfo> = emptyMap(),
    ): List<LibraryExportRow> {
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
            getEntryCustomInfo = mockk { coEvery { awaitAll() } returns customInfo },
        ).await()
    }
}
