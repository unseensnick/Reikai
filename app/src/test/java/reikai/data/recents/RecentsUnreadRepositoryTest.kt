package reikai.data.recents

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.recents.RecentsUnreadRepository
import tachiyomi.data.Chapters
import tachiyomi.data.Custom_manga_info
import tachiyomi.data.Custom_novel_info
import tachiyomi.data.Database
import tachiyomi.data.DateColumnAdapter
import tachiyomi.data.History
import tachiyomi.data.Mangas
import tachiyomi.data.MemoColumnAdapter
import tachiyomi.data.Novels
import tachiyomi.data.StringListColumnAdapter
import tachiyomi.data.UpdateStrategyColumnAdapter

/**
 * The recents surface's chapter-side reads, over a real schema. The write signal is what drops a
 * resolved continue-reading target, so it has to hear both tables a target is read from: the chapter
 * rows, and the stitch a merged row resolves over. Pinned once over both content types.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RecentsUnreadRepositoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: RecentsUnreadRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            database = Database(
                driver = driver,
                historyAdapter = History.Adapter(last_readAdapter = DateColumnAdapter),
                mangasAdapter = Mangas.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                    memoAdapter = MemoColumnAdapter,
                ),
                chaptersAdapter = Chapters.Adapter(memoAdapter = MemoColumnAdapter),
                novelsAdapter = Novels.Adapter(
                    genreAdapter = StringListColumnAdapter,
                    update_strategyAdapter = UpdateStrategyColumnAdapter,
                ),
                custom_manga_infoAdapter = Custom_manga_info.Adapter(genreAdapter = StringListColumnAdapter),
                custom_novel_infoAdapter = Custom_novel_info.Adapter(genreAdapter = StringListColumnAdapter),
            )
            repository = RecentsUnreadRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    /** How many times [probe]'s signal fired, counting the one it sends on collection, across [write]. */
    private fun TestScope.signalsAcross(probe: ChapterWriteProbe, write: suspend (Database) -> Unit): Int {
        var fired = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            probe.signal(repository).collect { fired++ }
        }
        advanceUntilIdle()
        launch { write(database) }
        advanceUntilIdle()
        return fired
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writeProbes")
    fun `a chapter write reaches the signal`(probe: ChapterWriteProbe) = runTest {
        signalsAcross(probe, probe.writeChapter) shouldBe 2
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("writeProbes")
    fun `a stitch write reaches the signal`(probe: ChapterWriteProbe) = runTest {
        signalsAcross(probe, probe.writeStitch) shouldBe 2
    }

    companion object {
        @JvmStatic
        fun writeProbes() = listOf(
            ChapterWriteProbe(
                label = "manga",
                signal = { it.mangaChapterWrites() },
                writeChapter = { it.chaptersQueries.removeChaptersWithIds(listOf(99L)) },
                writeStitch = { it.merged_chapter_unitQueries.deleteGroup(99L) },
            ),
            ChapterWriteProbe(
                label = "novels",
                signal = { it.novelChapterWrites() },
                writeChapter = { it.novel_chaptersQueries.delete(99L) },
                writeStitch = { it.merged_chapter_unitQueries.deleteNovelGroup(99L) },
            ),
        )
    }
}

/** One content type's write signal and the two kinds of write it has to hear. */
class ChapterWriteProbe(
    private val label: String,
    val signal: (RecentsUnreadRepository) -> Flow<Unit>,
    val writeChapter: suspend (Database) -> Unit,
    val writeStitch: suspend (Database) -> Unit,
) {
    override fun toString() = label
}
