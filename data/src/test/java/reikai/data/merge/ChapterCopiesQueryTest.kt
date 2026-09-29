package reikai.data.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.merge.MergedChapterUnitRepository.StoredUnit
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * The copies a recents row probes for a merged chapter, over both content types' tables on a created
 * schema. Entries 1 and 2 are in the library; entry 3 is not. Chapters 10, 20 and 30 are one merged
 * chapter on entries 1, 2 and 3; chapter 11 is the next one, on entry 1 alone; chapter 12 was dropped
 * by the stitch.
 */
class ChapterCopiesQueryTest {

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

    @ParameterizedTest
    @EnumSource(names = ["MANGA", "NOVELS"])
    fun `a chapter's copies are every library member's copy of it`(type: ContentType) = runTest {
        stitchedGroup(type)

        copyIds(type, 10L) shouldBe mapOf(10L to setOf(10L, 20L))
    }

    @ParameterizedTest
    @EnumSource(names = ["MANGA", "NOVELS"])
    fun `a copy on an entry outside the library is left out`(type: ContentType) = runTest {
        stitchedGroup(type)

        copyIds(type, 30L) shouldBe mapOf(30L to setOf(10L, 20L))
    }

    @ParameterizedTest
    @EnumSource(names = ["MANGA", "NOVELS"])
    fun `a chapter the stitch dropped has no copies`(type: ContentType) = runTest {
        stitchedGroup(type)

        copyIds(type, 12L) shouldBe emptyMap()
    }

    @ParameterizedTest
    @EnumSource(names = ["MANGA", "NOVELS"])
    fun `each chapter asked for keys its own copies`(type: ContentType) = runTest {
        stitchedGroup(type)

        copyIds(type, 10L, 11L) shouldBe mapOf(10L to setOf(10L, 20L), 11L to setOf(11L))
    }

    @Test
    fun `a manga copy an excluded scanlator hides is left out`() = runTest {
        stitchedGroup(ContentType.MANGA)
        driver.execute(null, "INSERT INTO excluded_scanlator(manga_id, scanlator) VALUES (2, 'hidden')", 0).await()

        copyIds(ContentType.MANGA, 10L) shouldBe mapOf(10L to setOf(10L))
    }

    @Test
    fun `a copy carries its owner's stored title and source`() = runTest {
        stitchedGroup(ContentType.MANGA)

        units.getCopiesAsFlow(ContentType.MANGA, listOf(10L)).first().getValue(10L)
            .first { it.copy.chapterId == 20L }
            .let { it.ownerTitle to it.ownerSource } shouldBe ("title 2" to "102")
    }

    private suspend fun copyIds(type: ContentType, vararg ids: Long): Map<Long, Set<Long>> =
        units.getCopiesAsFlow(type, ids.toList()).first()
            .mapValues { (_, copies) -> copies.mapTo(HashSet()) { it.copy.chapterId } }

    private suspend fun stitchedGroup(type: ContentType) {
        val novels = type == ContentType.NOVELS
        listOf(1L, 2L, 3L).forEach { id ->
            val favorite = if (id == 3L) "NULL" else "0"
            val entry = if (novels) {
                "INSERT INTO novels(_id, source, url, title, status, initialized, chapter_flags, favorite_at) " +
                    "VALUES ($id, 'src$id', 'n$id', 'title $id', 0, 0, 0, $favorite)"
            } else {
                "INSERT INTO manga(id, source_id, remote_url, remote_title, remote_status, state_initialized, " +
                    "user_reader_flags, user_chapter_flags, state_cover_last_modified, user_favorite_at, " +
                    "remote_update_strategy, state_chapter_fetch_interval, user_notes, remote_memo) VALUES " +
                    "($id, ${100 + id}, 'm$id', 'title $id', 0, 0, 0, 0, 0, $favorite, 0, 0, '', '{}')"
            }
            driver.execute(null, entry, 0).await()
        }
        listOf(10L to 1L, 11L to 1L, 12L to 1L, 20L to 2L, 30L to 3L).forEach { (chapter, owner) ->
            val row = if (novels) {
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "($chapter, $owner, 'u$chapter', 'c$chapter', 0, 0, 0, 1.0, 0, 0, 0)"
            } else {
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                    "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                    "remote_date_upload, remote_memo) VALUES ($chapter, $owner, 'u$chapter', 'c$chapter', " +
                    "'hidden', 0, 0, 0, 1.0, 0, 0, 0, '{}')"
            }
            driver.execute(null, row, 0).await()
        }
        val group = groups.createGroup(type, listOf(1L, 2L))!!
        units.replaceGroup(
            type,
            group,
            listOf(
                StoredUnit(10L, unit = 0, copyOrder = 0, chapterName = "c10", chapterNumber = 1.0),
                StoredUnit(20L, unit = 0, copyOrder = 1, chapterName = "c20", chapterNumber = 1.0),
                StoredUnit(30L, unit = 0, copyOrder = 2, chapterName = "c30", chapterNumber = 1.0),
                StoredUnit(11L, unit = 1, copyOrder = 0, chapterName = "c11", chapterNumber = 2.0),
                StoredUnit(12L, unit = null, copyOrder = 0, chapterName = "c12", chapterNumber = 3.0),
            ),
            ranking = null,
        )
    }
}
