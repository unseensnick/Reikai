package mihon.core.migration.migrations

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import mihon.core.migration.MigrationContext
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.library.ContentType
import reikai.domain.merge.GatherGroupViewerFlags
import reikai.domain.merge.TestMergeManagers
import reikai.domain.novel.NovelHistoryRepository
import reikai.domain.novel.NovelPageLayout
import reikai.domain.novel.NovelRenderingMode
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
 * types over one group (1, 2, 3) and an ungrouped entry (5), once per flag field each type keeps.
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
            GatherGroupViewerFlags(
                managers.manga,
                mangaRepository,
                historyRepository,
                managers.novel,
                novelRepository,
                novelHistoryRepository,
            ),
        )(MigrationContext(dryrun = false, previousVersion = 208))
    }

    /** One reader flag of [type]: member 1 chose [older], member 2, read more recently, chose [newer]. */
    class Field(val type: ContentType, private val name: String, val older: Long, val newer: Long) {
        override fun toString() = "$type $name"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fields")
    fun `every member takes the field the most recently read one chose`(field: Field) = runTest {
        flags.putAll(mapOf(1L to field.older, 2L to field.newer, 3L to 0L))

        migrate(field.type)

        listOf(flags[1L], flags[2L], flags[3L]) shouldBe List(3) { field.newer }
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `an ungrouped entry keeps its own flags`(type: ContentType) = runTest {
        migrate(type)

        flags[5L] shouldBe 0x18L
    }

    companion object {
        private fun rotation(o: ReaderOrientation) = o.flagValue.toLong()

        @JvmStatic
        fun fields() = listOf(
            Field(
                ContentType.MANGA,
                "reading mode",
                ReadingMode.RIGHT_TO_LEFT.flagValue.toLong(),
                ReadingMode.VERTICAL.flagValue.toLong(),
            ),
            Field(
                ContentType.MANGA,
                "rotation",
                rotation(ReaderOrientation.PORTRAIT),
                rotation(ReaderOrientation.LANDSCAPE),
            ),
            Field(
                ContentType.NOVELS,
                "rotation",
                rotation(ReaderOrientation.PORTRAIT),
                rotation(ReaderOrientation.LANDSCAPE),
            ),
            Field(
                ContentType.NOVELS,
                "rendering mode",
                NovelRenderingMode.flagOf(NovelRenderingMode.NATIVE),
                NovelRenderingMode.flagOf(NovelRenderingMode.WEBVIEW),
            ),
            Field(
                ContentType.NOVELS,
                "reading mode",
                NovelPageLayout.flagOf(NovelPageLayout.SCROLL),
                NovelPageLayout.flagOf(NovelPageLayout.PAGED_RTL),
            ),
        )
    }
}
