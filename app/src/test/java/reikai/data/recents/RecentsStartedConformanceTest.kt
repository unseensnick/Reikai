package reikai.data.recents

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.reader.ChapterProgress
import reikai.presentation.recents.RecentsChapterFilters
import reikai.presentation.recents.chapterState
import tachiyomi.core.common.preference.TriState
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.updates.UpdatesRepositoryImpl

/**
 * The Started filter has one meaning on every lane of a view. The updated lane asks it in SQL, Mihon's
 * query unchanged, and the read lane asks it of the chapter-filter kernel, so each chapter state is put
 * to both and the two must agree, on both content types. They once disagreed on a finished chapter,
 * which the read lane kept as started while the updated lane dropped it.
 */
class RecentsStartedConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            database = DatabaseBindings.providesDatabase(driver)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest(name = "{0}: started {1}, read {2}, progress {3}")
    @MethodSource("cases")
    fun `the updated lane's query and the read lane's kernel keep the same chapters`(
        probe: StartedConformanceProbe,
        started: TriState,
        read: Boolean,
        progress: Long,
    ) = runTest {
        probe.seed(database, driver, read, progress)

        val keptBySql = probe.keptBySql(database, started == TriState.ENABLED_IS)
        val keptByKernel = RecentsChapterFilters(started = started)
            .matches(chapterState(read = read, bookmark = false, progress = probe.progress(progress))) { false }

        keptByKernel shouldBe keptBySql
    }

    companion object {
        private val probes = listOf(
            StartedConformanceProbe(
                label = "manga",
                progress = { ChapterProgress.Pages(it, pageCount = 20) },
                seed = { _, driver, read, progress ->
                    driver.execute(
                        null,
                        "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, " +
                            "state_initialized, user_reader_flags, user_chapter_flags, " +
                            "state_cover_last_modified, user_favorite_at, remote_update_strategy, " +
                            "state_chapter_fetch_interval, user_notes, remote_memo) VALUES (1, 1, 'm', 't', " +
                            "0, 0, 0, 0, 0, 0, 0, 0, '', '{}')",
                        0,
                    ).await()
                    driver.execute(
                        null,
                        "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, " +
                            "user_read, user_bookmark, user_last_page_read, remote_chapter_number, " +
                            "remote_order, state_date_fetch, remote_date_upload, remote_memo) VALUES (1, 1, " +
                            "'c', 'n', NULL, ${read.toSql()}, 0, $progress, 1.0, 0, 1000, 1000, '{}')",
                        0,
                    ).await()
                },
                keptBySql = { database, started ->
                    UpdatesRepositoryImpl(database).subscribeAll(
                        after = 0,
                        limit = 10,
                        unread = null,
                        started = started,
                        bookmarked = null,
                        hideExcludedScanlators = false,
                        includedCategories = emptyList(),
                        excludedCategories = emptyList(),
                    ).first().isNotEmpty()
                },
            ),
            StartedConformanceProbe(
                label = "novels",
                progress = { ChapterProgress.Percent(it) },
                seed = { _, driver, read, progress ->
                    driver.execute(
                        null,
                        "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, " +
                            "favorite_at) VALUES (1, 'src', 'n', 't', 0, 0, 0, 0)",
                        0,
                    ).await()
                    driver.execute(
                        null,
                        "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                            "chapter_number, source_order, date_fetch, date_upload) " +
                            "VALUES (1, 1, 'c', 'n', ${read.toSql()}, 0, $progress, 1.0, 0, 1000, 1000)",
                        0,
                    ).await()
                },
                keptBySql = { database, started ->
                    NovelRepositoryImpl(database).getFilteredNovelUpdatesAsFlow(
                        after = 0,
                        limit = 10,
                        unread = null,
                        started = started,
                        bookmarked = null,
                        includedCategories = emptyList(),
                        excludedCategories = emptyList(),
                    ).first().isNotEmpty()
                },
            ),
        )

        private fun Boolean.toSql(): Int = if (this) 1 else 0

        /** Every chapter state, finished or not and opened or not, under both answers to Started. */
        @JvmStatic
        fun cases(): List<Arguments> = probes.flatMap { probe ->
            listOf(TriState.ENABLED_IS, TriState.ENABLED_NOT).flatMap { started ->
                listOf(false to 0L, false to 3L, true to 0L, true to 3L).map { (read, progress) ->
                    Arguments.of(probe, started, read, progress)
                }
            }
        }
    }
}

/** One content type's half: how it stores a chapter's progress, and how its updated lane's query asks. */
class StartedConformanceProbe(
    private val label: String,
    val progress: (Long) -> ChapterProgress,
    val seed: suspend (Database, JdbcSqliteDriver, Boolean, Long) -> Unit,
    val keptBySql: suspend (Database, Boolean) -> Boolean,
) {
    override fun toString() = label
}
