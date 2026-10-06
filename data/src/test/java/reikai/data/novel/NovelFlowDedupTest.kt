package reikai.data.novel

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
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * SQLite tells a query only that its table was written, so a write to one novel re-runs every other
 * novel's query. The details page restarts its chapter pipeline on each emission of these flows, so
 * they emit only when the value they carry changed.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NovelFlowDedupTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var novels: NovelRepositoryImpl
    private lateinit var chapters: NovelChapterRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            val database = DatabaseBindings.providesDatabase(driver)
            novels = NovelRepositoryImpl(database)
            chapters = NovelChapterRepositoryImpl(database)
            listOf(1L, 2L).forEach { id ->
                novels.insert(Novel.create().copy(source = "src", url = "n-$id", title = "novel $id"))
                chapters.insert(chapter(novelId = id, url = "c-$id"))
            }
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a write to another novel adds no emission`(probe: FlowProbe) = runTest {
        emissionsAcross(probe.subscribe(novels, chapters, 1L)) { probe.write(novels, chapters, 2L) } shouldBe 1
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a write to the same novel still emits`(probe: FlowProbe) = runTest {
        emissionsAcross(probe.subscribe(novels, chapters, 1L)) { probe.write(novels, chapters, 1L) } shouldBe 2
    }

    class FlowProbe(
        private val label: String,
        val subscribe: (NovelRepositoryImpl, NovelChapterRepositoryImpl, Long) -> Flow<*>,
        val write: suspend (NovelRepositoryImpl, NovelChapterRepositoryImpl, Long) -> Unit,
    ) {
        override fun toString() = label
    }

    companion object {
        private const val PAGE = "1"

        private fun chapter(novelId: Long, url: String) = NovelChapter(
            id = -1L, novelId = novelId, url = url, name = url, read = false, bookmark = false,
            lastTextProgress = 0L, chapterNumber = 1.0, sourceOrder = 0L, dateFetch = 0L,
            dateUpload = 0L, page = PAGE,
        )

        private val addChapter: suspend (NovelRepositoryImpl, NovelChapterRepositoryImpl, Long) -> Unit =
            { _, chapters, id -> chapters.insert(chapter(novelId = id, url = "added-$id")) }

        @JvmStatic
        fun probes() = listOf(
            FlowProbe(
                "novel row",
                { novels, _, id -> novels.getByUrlAndSourceAsFlow("n-$id", "src") },
                { novels, _, id -> novels.update(NovelUpdate(id) { title = "renamed $id" }) },
            ),
            FlowProbe("chapter list", { _, chapters, id -> chapters.getByNovelIdAsFlow(id) }, addChapter),
            FlowProbe(
                "page chapter list",
                { _, chapters, id -> chapters.getByNovelIdAndPageAsFlow(id, PAGE) },
                addChapter,
            ),
        )
    }
}
