package mihon.core.migration.migrations

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.merge.TestMergeManagers
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelHistory
import tachiyomi.domain.history.model.History
import tachiyomi.domain.history.repository.HistoryRepository
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Instant

/**
 * The upgrade that gathers a merged series' reader flags onto the whole group, run for both content
 * types over one group (1, 2, 3) and an ungrouped entry (5). Rotation is a field both types keep.
 */
class MergedViewerFlagsMigrationTest {

    private val flags = mutableMapOf(1L to 0x08L, 2L to 0x10L, 3L to 0L, 5L to 0x18L)
    private val lastReadAt = mapOf(1L to 10L, 2L to 20L, 3L to 5L, 5L to 30L)

    private fun write(id: Long, flag: Long, mask: Long): Boolean {
        flags[id] = (flags.getValue(id) and mask.inv()) or flag
        return true
    }

    private val mangaRepository = mockk<MangaRepository> {
        coEvery { getMangaById(any()) } answers {
            mockk<Manga> { every { viewerFlags } returns flags.getValue(firstArg()) }
        }
        coEvery { setViewerFlags(any(), any(), any()) } answers { write(firstArg(), secondArg(), thirdArg()) }
    }
    private val historyRepository = mockk<HistoryRepository> {
        coEvery { getHistoryByMangaId(any()) } answers {
            listOf(
                mockk<History> {
                    every { readAt } returns
                        Instant.fromEpochMilliseconds(lastReadAt.getValue(firstArg()))
                },
            )
        }
    }
    private val novelRepository = mockk<NovelRepository> {
        coEvery { getById(any()) } answers {
            mockk<Novel> { every { viewerFlags } returns flags.getValue(firstArg()) }
        }
        coEvery { setViewerFlags(any(), any(), any()) } answers { write(firstArg(), secondArg(), thirdArg()) }
    }
    private val novelHistoryRepository = mockk<NovelHistoryRepository> {
        coEvery { getHistoryByNovelId(any()) } answers {
            listOf(NovelHistory(0L, Instant.fromEpochMilliseconds(lastReadAt.getValue(firstArg())), 0L))
        }
    }

    private suspend fun migrate(type: ContentType) {
        val managers = TestMergeManagers(mapOf(type to linkedMapOf(1L to 7L, 2L to 7L, 3L to 7L)), mergingOn = true)
        MergedViewerFlagsMigration(
            managers.manga,
            mangaRepository,
            historyRepository,
            managers.novel,
            novelRepository,
            novelHistoryRepository,
        )(MigrationContext(dryrun = false, previousVersion = 208))
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `every member takes the rotation of the most recently read one`(type: ContentType) = runTest {
        migrate(type)

        listOf(flags[1L], flags[2L], flags[3L]) shouldBe listOf(0x10L, 0x10L, 0x10L)
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `an ungrouped entry keeps its own flags`(type: ContentType) = runTest {
        migrate(type)

        flags[5L] shouldBe 0x18L
    }
}
