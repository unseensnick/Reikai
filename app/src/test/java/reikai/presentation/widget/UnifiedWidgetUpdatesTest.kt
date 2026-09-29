package reikai.presentation.widget

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.collections.shouldBeEmpty
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.novel.NovelRepositoryImpl
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.data.updates.UpdatesRepositoryImpl
import tachiyomi.domain.updates.interactor.GetUpdates

/**
 * The widget shows unread updates only, and its refresh driver watches the same rows, so reading a
 * chapter has to change that set on both content types or the driver never redraws the widget.
 */
internal class UnifiedWidgetUpdatesTest {

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

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a read chapter leaves the widget's set on either content type`(probe: WidgetUpdatesProbe) = runTest {
        probe.seed(driver, System.currentTimeMillis())
        probe.markRead(driver)

        val updates = unifiedWidgetUpdates(GetUpdates(UpdatesRepositoryImpl(database)), NovelRepositoryImpl(database))
            .first()

        probe.chapterIds(updates).shouldBeEmpty()
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(
            WidgetUpdatesProbe(
                label = "manga",
                seed = { driver, now ->
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
                            "'c', 'n', NULL, 0, 0, 0, 1.0, 0, $now, $now, '{}')",
                        0,
                    ).await()
                },
                markRead = { driver ->
                    driver.execute(null, "UPDATE chapter SET user_read = 1 WHERE id = 1", 0).await()
                },
                chapterIds = { updates -> updates.manga.map { it.chapterId } },
            ),
            WidgetUpdatesProbe(
                label = "novels",
                seed = { driver, now ->
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
                            "VALUES (1, 1, 'c', 'n', 0, 0, 0, 1.0, 0, $now, $now)",
                        0,
                    ).await()
                },
                markRead = { driver ->
                    driver.execute(null, "UPDATE novel_chapters SET read = 1 WHERE _id = 1", 0).await()
                },
                chapterIds = { updates -> updates.novel.map { it.chapterId } },
            ),
        )
    }
}

/** One content type's half: how a recent unread chapter is stored, read, and found in the widget's set. */
internal class WidgetUpdatesProbe(
    private val label: String,
    val seed: suspend (JdbcSqliteDriver, Long) -> Unit,
    val markRead: suspend (JdbcSqliteDriver) -> Unit,
    val chapterIds: (UnifiedWidgetUpdates) -> List<Long>,
) {
    override fun toString() = label
}
