package reikai.data.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.data.recents.RecentsUnreadRepositoryImpl
import reikai.domain.library.ContentType
import reikai.domain.merge.MergedChapterUnitRepository.StoredUnit
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings

/**
 * Every reader of a merged group's counted copies must leave out the same ones: a copy on a member
 * outside the library, and on manga one an excluded scanlator hides. The group counts, the recents
 * unread filter, the download units and a chapter's copies are asked about one fixture, so a reader
 * that drifts from the shared definition fails here. Entries 1 and 2 are in the library, entry 3 is
 * not; chapter 10 is entry 1's copy of the group's one merged chapter, and the other copy is the
 * only read one.
 */
class MergedUnitSetConformanceTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var units: MergedChapterUnitRepositoryImpl
    private lateinit var groups: MergeGroupRepositoryImpl
    private lateinit var recents: RecentsUnreadRepositoryImpl

    /** What each reader answers for the fixture's group. */
    private data class Answers(
        val unread: Long?,
        val entriesWithUnread: Set<Long>,
        val downloadOwners: Set<Long>,
        val copiesOfChapter10: Set<Long>,
    )

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
            val database = DatabaseBindings.providesDatabase(driver)
            units = MergedChapterUnitRepositoryImpl(database)
            groups = MergeGroupRepositoryImpl(database)
            recents = RecentsUnreadRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    @ParameterizedTest
    @EnumSource(names = ["MANGA", "NOVELS"])
    fun `a copy on a member outside the library is counted by no reader`(type: ContentType) = runTest {
        // The window before reconciliation restitches a group whose member just left the library.
        val group = stitchedGroup(type, otherCopy = 30L, otherOwner = 3L)

        answers(type, group) shouldBe Answers(1, setOf(1L, 2L), setOf(1L), setOf(10L))
    }

    @ParameterizedTest
    @EnumSource(names = ["MANGA", "NOVELS"])
    fun `a copy an excluded scanlator hides is counted by no reader`(type: ContentType) = runTest {
        assumeTrue(type == ContentType.MANGA, "novels have no scanlators to exclude")
        val group = stitchedGroup(type, otherCopy = 20L, otherOwner = 2L)
        driver.execute(null, "INSERT INTO excluded_scanlator(manga_id, scanlator) VALUES (2, 'hidden')", 0).await()

        answers(type, group) shouldBe Answers(1, setOf(1L, 2L), setOf(1L), setOf(10L))
    }

    private suspend fun answers(type: ContentType, group: Long): Answers {
        val entriesWithUnread = when (type) {
            ContentType.NOVELS -> recents.subscribeNovelIdsWithUnread(mergingEnabled = true)
            else -> recents.subscribeMangaIdsWithUnread(mergingEnabled = true)
        }.first()
        return Answers(
            unread = units.getGroupCounts(type)[group]?.unread,
            entriesWithUnread = entriesWithUnread,
            downloadOwners = units.getDownloadUnitsAsFlow(type).first()[group].orEmpty().mapTo(HashSet()) {
                it.ownerId
            },
            copiesOfChapter10 = units.getCopiesAsFlow(type, listOf(10L)).first()[10L].orEmpty()
                .mapTo(HashSet()) { it.copy.chapterId },
        )
    }

    /** Entries 1, 2 and 3 grouped, with chapter 10 and [otherCopy] stitched as one merged chapter. */
    private suspend fun stitchedGroup(type: ContentType, otherCopy: Long, otherOwner: Long): Long {
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
        listOf(Triple(10L, 1L, 0), Triple(otherCopy, otherOwner, 1)).forEach { (chapter, owner, read) ->
            val row = if (novels) {
                "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                    "chapter_number, source_order, date_fetch, date_upload) VALUES " +
                    "($chapter, $owner, 'u$chapter', 'c$chapter', $read, 0, 0, 1.0, 0, 0, 0)"
            } else {
                val scanlator = if (chapter == 10L) "NULL" else "'hidden'"
                "INSERT INTO chapter(id, manga_id, remote_url, remote_name, remote_scanlator, user_read, " +
                    "user_bookmark, user_last_page_read, remote_chapter_number, remote_order, state_date_fetch, " +
                    "remote_date_upload, remote_memo) VALUES ($chapter, $owner, 'u$chapter', 'c$chapter', " +
                    "$scanlator, $read, 0, 0, 1.0, 0, 0, 0, '{}')"
            }
            driver.execute(null, row, 0).await()
        }
        val group = groups.createGroup(type, listOf(1L, 2L, 3L))!!
        units.replaceGroup(
            type,
            group,
            listOf(
                StoredUnit(10L, unit = 0, copyOrder = 0, chapterName = "c10", chapterNumber = 1.0, sourceOrder = 0),
                StoredUnit(
                    otherCopy,
                    unit = 0,
                    copyOrder = 1,
                    chapterName = "c$otherCopy",
                    chapterNumber = 1.0,
                    sourceOrder = 0,
                ),
            ),
            ranking = null,
        )
        return group
    }
}
