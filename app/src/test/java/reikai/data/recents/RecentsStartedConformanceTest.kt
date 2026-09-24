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
                        "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                            "chapter_flags, cover_last_modified, date_added) VALUES (1, 1, 'm', 't', 0, 1, 0, 0, 0, 0, 0)",
                        0,
                    ).await()
                    driver.execute(
                        null,
                        "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read, " +
                            "chapter_number, source_order, date_fetch, date_upload) " +
                            "VALUES (1, 1, 'c', 'n', NULL, ${read.toSql()}, 0, $progress, 1.0, 0, 1000, 1000)",
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
                        "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags, " +
                            "date_added) VALUES (1, 'src', 'n', 't', 0, 1, 0, 0, 0)",
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
