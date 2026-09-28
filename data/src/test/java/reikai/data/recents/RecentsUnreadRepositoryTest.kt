package reikai.data.recents

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.merge.MergeGroupRepositoryImpl
import reikai.domain.library.ContentType
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

    // Which entries still have something to read. A kept row has to resolve a chapter on a tap, and the
    // tap resolves over the merge group with excluded scanlators left out, so the set answers the same
    // question: judged per entry, a member with unread copies kept a series the group had finished,
    // and a member read on its own dropped a series the group had not.

    private suspend fun seed(vararg sql: String) = sql.forEach { driver.execute(null, it, 0).await() }

    private suspend fun unread(probe: UnreadProbe, merging: Boolean = true): Set<Long> =
        probe.unread(repository, merging).first()

    /** Library members 1 and 2, grouped, with their chapters 10 and 20 stitched as one unit. */
    private suspend fun groupedPair(probe: UnreadProbe, read1: Boolean, read2: Boolean, inLibrary1: Boolean = true) {
        seed(
            probe.entry(1, inLibrary1),
            probe.entry(2, true),
            probe.chapter(10, 1, read1, null),
            probe.chapter(20, 2, read2, null),
        )
        val group = MergeGroupRepositoryImpl(database).createGroup(probe.type, listOf(1L, 2L))!!
        seed(probe.unit(10, group, 0), probe.unit(20, group, 0))
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadProbes")
    fun `a group read on another source leaves no member unread`(probe: UnreadProbe) = runTest {
        groupedPair(probe, read1 = false, read2 = true)

        unread(probe) shouldBe emptySet()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadProbes")
    fun `a group with a chapter left keeps a member whose own copies are all read`(probe: UnreadProbe) =
        runTest {
            groupedPair(probe, read1 = true, read2 = true)
            val group = MergeGroupRepositoryImpl(database).getAllMemberships(probe.type).getValue(1L)
            seed(probe.chapter(21, 2, false, null), probe.unit(21, group, 1))

            unread(probe) shouldBe setOf(1L, 2L)
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadProbes")
    fun `a group nothing has stitched yet leaves each member to its own chapters`(probe: UnreadProbe) = runTest {
        seed(
            probe.entry(1, true),
            probe.entry(2, true),
            probe.chapter(10, 1, false, null),
            probe.chapter(20, 2, true, null),
        )
        MergeGroupRepositoryImpl(database).createGroup(probe.type, listOf(1L, 2L))

        unread(probe) shouldBe setOf(1L)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadProbes")
    fun `with merging off each member answers for itself`(probe: UnreadProbe) = runTest {
        groupedPair(probe, read1 = false, read2 = true)

        unread(probe, merging = false) shouldBe setOf(1L)
    }

    /** The group count reads library members only, while the read lane shows entries that are not. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("unreadProbes")
    fun `a member outside the library answers for itself`(probe: UnreadProbe) = runTest {
        groupedPair(probe, read1 = false, read2 = true, inLibrary1 = false)

        unread(probe) shouldBe setOf(1L)
    }

    /** Novels have no scanlators, so this rule has no novel case. */
    @Test
    fun `a manga whose only unread chapter is by an excluded scanlator has nothing left`() = runTest {
        val manga = unreadProbes().first()
        seed(
            manga.entry(1, true),
            manga.chapter(10, 1, false, "hidden"),
            "INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'hidden')",
        )

        unread(manga) shouldBe emptySet()
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
        private fun Boolean.toSql(): Int = if (this) 1 else 0

        @JvmStatic
        fun unreadProbes() = listOf(
            UnreadProbe(
                label = "manga",
                type = ContentType.MANGA,
                unread = { repository, merging -> repository.subscribeMangaIdsWithUnread(merging) },
                entry = { id, favorite ->
                    "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                        "chapter_flags, cover_last_modified, date_added) " +
                        "VALUES ($id, 1, 'm-$id', 't', 0, ${favorite.toSql()}, 0, 0, 0, 0, 0)"
                },
                chapter = { id, entryId, read, scanlator ->
                    val scanlatorSql = scanlator?.let { "'$it'" } ?: "NULL"
                    "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, last_page_read, " +
                        "chapter_number, source_order, date_fetch, date_upload) " +
                        "VALUES ($id, $entryId, 'c-$id', 'n', $scanlatorSql, ${read.toSql()}, 0, 0, 1.0, 0, 0, 0)"
                },
                unit = { chapterId, groupId, unit ->
                    "INSERT INTO merged_chapter_unit(chapter_id, group_id, unit, copy_order, derived_number) " +
                        "VALUES ($chapterId, $groupId, $unit, $chapterId, 1.0)"
                },
            ),
            UnreadProbe(
                label = "novels",
                type = ContentType.NOVELS,
                unread = { repository, merging -> repository.subscribeNovelIdsWithUnread(merging) },
                entry = { id, favorite ->
                    "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags, " +
                        "date_added) VALUES ($id, 'src', 'n-$id', 't', 0, ${favorite.toSql()}, 0, 0, 0)"
                },
                chapter = { id, entryId, read, _ ->
                    "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, last_text_progress, " +
                        "chapter_number, source_order, date_fetch, date_upload) " +
                        "VALUES ($id, $entryId, 'c-$id', 'n', ${read.toSql()}, 0, 0, 1.0, 0, 0, 0)"
                },
                unit = { chapterId, groupId, unit ->
                    "INSERT INTO merged_novel_chapter_unit(chapter_id, group_id, unit, copy_order, derived_name, " +
                        "derived_number) VALUES ($chapterId, $groupId, $unit, $chapterId, 'n', 1.0)"
                },
            ),
        )

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

/** One content type's unread query, and the SQL that seeds the tables it reads. */
class UnreadProbe(
    private val label: String,
    val type: ContentType,
    val unread: (RecentsUnreadRepository, Boolean) -> Flow<Set<Long>>,
    val entry: (id: Long, favorite: Boolean) -> String,
    /** The scanlator is ignored on novels, which have none. */
    val chapter: (id: Long, entryId: Long, read: Boolean, scanlator: String?) -> String,
    val unit: (chapterId: Long, groupId: Long, unit: Int) -> String,
) {
    override fun toString() = label
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
