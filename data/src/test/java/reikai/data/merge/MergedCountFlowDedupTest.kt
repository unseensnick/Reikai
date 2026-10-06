package reikai.data.merge

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
import reikai.domain.library.ContentType
import reikai.domain.merge.MergedChapterUnitRepository.StoredUnit
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * The merged counts the library folds into every rebuild re-run on any write to the chapter and entry
 * tables, since SQLite names only the table that changed. They emit only when the value they carry
 * changed, so a write to an ungrouped entry costs the library no extra rebuild. Entries 1 and 2 are
 * grouped and stitched as one merged chapter (10 and 20); entry 4 is in the library on its own.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MergedCountFlowDedupTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var units: MergedChapterUnitRepositoryImpl
    private lateinit var groups: MergeGroupRepositoryImpl

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a write to an ungrouped entry adds no emission`(probe: CountProbe) = runTest {
        stitchedGroup(probe.type)

        emissionsAcross(probe.subscribe(units)) {
            write(probe.type, chapterRow(probe.type, id = 40, owner = 4, number = 1.0))
        } shouldBe 1
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a write the count reads still emits`(probe: CountProbe) = runTest {
        stitchedGroup(probe.type)

        emissionsAcross(probe.subscribe(units)) { write(probe.type, probe.countedWrite) } shouldBe 2
    }

    /** How many times [flow] emitted, counting the emission on collection, across [write]. */
    private fun TestScope.emissionsAcross(flow: Flow<*>, write: suspend () -> Unit): Int {
        var emitted = 0
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { flow.collect { emitted++ } }
        advanceUntilIdle()
        launch { write() }
        advanceUntilIdle()
        return emitted
    }

    /** A raw write announced the way a generated query announces one, by the table it touched. */
    private suspend fun write(type: ContentType, sql: String) {
        driver.execute(null, sql, 0).await()
        driver.notifyListeners(chapterTable(type))
    }

    private suspend fun stitchedGroup(type: ContentType) {
        val novels = type == ContentType.NOVELS
        listOf(1L, 2L, 4L).forEach { id ->
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
        driver.execute(null, chapterRow(type, id = 10, owner = 1, number = 1.0), 0).await()
        driver.execute(null, chapterRow(type, id = 20, owner = 2, number = 1.0), 0).await()
        val group = groups.createGroup(type, listOf(1L, 2L))!!
        units.replaceGroup(
            type,
            group,
            listOf(10L, 20L).mapIndexed { copyOrder, chapter ->
                StoredUnit(
                    chapter,
                    unit = 0,
                    copyOrder,
                    chapterName = "c$chapter",
                    chapterNumber = 1.0,
                    sourceOrder = 0,
                )
            },
            ranking = null,
        )
    }

    class CountProbe(
        private val label: String,
        val type: ContentType,
        val subscribe: (MergedChapterUnitRepositoryImpl) -> Flow<*>,
        /** A write that changes what [subscribe] carries. */
        val countedWrite: String,
    ) {
        override fun toString() = "$label, $type"
    }

    companion object {
        private fun chapterTable(type: ContentType) = if (type == ContentType.NOVELS) "novel_chapters" else "chapter"

        private fun chapterRow(type: ContentType, id: Long, owner: Long, number: Double) =
            if (type == ContentType.NOVELS) {
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "($id, $owner, 'u$id', 'c$id', 0, 0, 0, $number, 0, 0, 0)"
            } else {
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                    "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                    "remote_date_upload, remote_memo) VALUES ($id, $owner, 'u$id', 'c$id', NULL, 0, 0, 0, " +
                    "$number, 0, 0, 0, '{}')"
            }

        private fun bookmarkChapter10(type: ContentType) = if (type == ContentType.NOVELS) {
            "UPDATE novel_chapters SET bookmark = 1 WHERE _id = 10"
        } else {
            "UPDATE chapter SET user_bookmark = 1 WHERE id = 10"
        }

        private fun renameChapter10(type: ContentType) = if (type == ContentType.NOVELS) {
            "UPDATE novel_chapters SET name = 'renamed' WHERE _id = 10"
        } else {
            "UPDATE chapter SET remote_name = 'renamed' WHERE id = 10"
        }

        @JvmStatic
        fun probes() = listOf(ContentType.MANGA, ContentType.NOVELS).flatMap { type ->
            listOf(
                CountProbe("group counts", type, { it.getGroupCountsAsFlow(type) }, bookmarkChapter10(type)),
                CountProbe("download units", type, { it.getDownloadUnitsAsFlow(type) }, renameChapter10(type)),
            )
        } + CountProbe(
            "recognized chapter counts",
            ContentType.MANGA,
            { it.getRecognizedChapterCountsAsFlow() },
            chapterRow(ContentType.MANGA, id = 11, owner = 1, number = 2.0),
        )
    }
}
