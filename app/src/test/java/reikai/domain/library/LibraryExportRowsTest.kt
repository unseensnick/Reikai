package reikai.domain.library

import eu.kanade.tachiyomi.data.export.LibraryExporter
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryCustomInfo
import reikai.domain.entry.EntryId
import reikai.domain.merge.TestMergeManagers
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.LibraryNovel
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
    fun `a merged series with nothing to rank its members on is written once, as its lowest id member`() =
        runTest {
            exportOf(mergingOn = true).map { it.title } shouldBe listOf("First source", "Novel one")
        }

    /** Edit info is kept on the member the library row leads on, which a preferred source can make any. */
    @Test
    fun `a merged series is written as the member its library row leads on, under that member's Edit info`() =
        runTest {
            val custom = mapOf<EntryId, EntryCustomInfo>(
                EntryId.Manga(2) to CustomMangaInfo(mangaId = 2, title = "Edited manga"),
                EntryId.Novel(2) to CustomNovelInfo(novelId = 2, title = "Edited novel"),
            )

            exportOf(mergingOn = true, customInfo = custom, preferSecondSources = true).map { it.title } shouldBe
                listOf("Edited manga", "Edited novel")
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
        preferSecondSources: Boolean = false,
    ): List<LibraryExportRow> {
        val managers = TestMergeManagers(
            memberships = mapOf(
                ContentType.MANGA to mapOf(1L to 10L, 2L to 10L),
                ContentType.NOVELS to mapOf(1L to 20L, 2L to 20L),
            ),
            mergingOn = mergingOn,
        )
        // Seeded through the mock: an in-memory store never hands back what was set on it.
        val preferences = mockk<ReikaiLibraryPreferences> {
            every { preferredMangaSources.get() } returns
                listOfNotNull(SECOND_MANGA_SOURCE.takeIf { preferSecondSources })
            every { preferredNovelSources.get() } returns
                listOfNotNull(SECOND_NOVEL_SOURCE.takeIf { preferSecondSources })
        }
        return GetLibraryExportRows(
            getFavorites = mockk<GetFavorites> {
                coEvery { await() } returns listOf(
                    manga(2, "Second source").copy(source = SECOND_MANGA_SOURCE),
                    manga(1, "First source"),
                )
            },
            novelRepository = mockk<NovelRepository> {
                every { getLibraryNovelAsFlow() } returns flowOf(
                    listOf(
                        libraryNovel(novel(2, "Novel two").copy(source = SECOND_NOVEL_SOURCE)),
                        libraryNovel(novel(1, "Novel one")),
                    ),
                )
            },
            mangaMergeManager = managers.manga,
            novelMergeManager = managers.novel,
            mergedChapterUnitRepository = mockk {
                every { getRecognizedChapterCountsAsFlow() } returns flowOf(emptyMap())
            },
            reikaiLibraryPreferences = preferences,
            getEntryCustomInfo = mockk { coEvery { awaitAll() } returns customInfo },
        ).await()
    }

    private fun libraryNovel(novel: Novel) = LibraryNovel(
        novel = novel,
        categories = emptyList(),
        totalChapters = 1L,
        readCount = 0L,
        bookmarkCount = 0L,
        downloadCount = 0L,
        latestUpload = 0L,
        chapterFetchedAt = 0L,
        lastRead = 0L,
    )

    private companion object {
        const val SECOND_MANGA_SOURCE = 200L
        const val SECOND_NOVEL_SOURCE = "second"
    }
}
