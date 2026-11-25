package reikai.presentation.library

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.data.merge.MergedChapterUnitRepositoryImpl
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.MergedChapterUnitRepository.GroupStitch
import reikai.domain.merge.MergedChapterUnitRepository.StoredUnit
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * The library reads a merged group's download rows when it rebuilds, so the collapse inputs have to
 * re-emit after any stitch written, including one that only re-pairs chapters and so leaves every count
 * as it was. Entries 1 and 2 are grouped; chapter 20 moves from entry 1's first merged chapter to its
 * second, which changes which merged chapter a copy on disk fills.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MergeCollapseInputsTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var units: MergedChapterUnitRepositoryImpl
    private lateinit var groups: MergeGroupRepositoryImpl

    private val prefs = ReikaiLibraryPreferences(EmittingPreferenceStore())

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            val database = DatabaseBindings.providesDatabase(driver)
            units = MergedChapterUnitRepositoryImpl(database)
            groups = MergeGroupRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(names = ["MANGA", "NOVELS"])
    fun `a restitch that only re-pairs chapters re-emits`(type: ContentType) = runTest {
        val group = groupOfTwo(type)
        units.replaceGroup(type, group, stitch(20L to 0), ranking = "r")
        var emitted = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            mergeCollapseInputsFlow(
                type,
                flowOf(emptyList<Long>()),
                prefs,
                groups,
                units,
            ).collect { emitted++ }
        }
        advanceUntilIdle()
        val before = emitted

        units.replaceGroup(type, group, stitch(20L to 1), ranking = "r")
        advanceUntilIdle()

        emitted shouldBe before + 1
    }

    /** Entry 1's chapters 10 and 11 as merged chapters 0 and 1, and entry 2's chapter 20 at [copy]'s unit. */
    private fun stitch(copy: Pair<Long, Int>) = GroupStitch(
        listOf(
            StoredUnit(10L, unit = 0, copyOrder = 0, chapterName = "c10", chapterNumber = 1.0, sourceOrder = 0),
            StoredUnit(11L, unit = 1, copyOrder = 0, chapterName = "c11", chapterNumber = 2.0, sourceOrder = 1),
            StoredUnit(copy.first, copy.second, copyOrder = 1, "c20", chapterNumber = 1.0, sourceOrder = 0),
        ),
        recognizedCounts = emptyMap(),
    )

    private suspend fun groupOfTwo(type: ContentType): Long {
        val novels = type == ContentType.NOVELS
        listOf(1L, 2L).forEach { id ->
            val entry = if (novels) {
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES ($id, 'src$id', 'n$id', 'title $id', 0, 0, 0, 0)"
            } else {
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "($id, ${100 + id}, 'm$id', 'title $id', 0, 0, 0, 0, 0, 0, 0, 0, '', '{}')"
            }
            driver.execute(null, entry, 0).await()
        }
        listOf(Triple(10L, 1L, 1.0), Triple(11L, 1L, 2.0), Triple(20L, 2L, 1.0)).forEach { (id, owner, number) ->
            val row = if (novels) {
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "($id, $owner, 'u$id', 'c$id', 0, 0, 0, $number, 0, 0, 0)"
            } else {
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                    "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                    "remote_date_upload, remote_memo) VALUES ($id, $owner, 'u$id', 'c$id', NULL, 0, 0, 0, " +
                    "$number, 0, 0, 0, '{}')"
            }
            driver.execute(null, row, 0).await()
        }
        return groups.createGroup(type, listOf(1L, 2L))!!
    }
}
