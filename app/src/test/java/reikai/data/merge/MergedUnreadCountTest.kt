package reikai.data.merge

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType
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
 * Exercises the deduplicated merged unread count against a real in-memory database. The rule under
 * test: a chapter counts as unread only when NO member source's copy of it is read, so the number is
 * independent of which source currently leads the group.
 *
 * The two content types deliberately dedup differently (manga collapses a source's own scanlator
 * variants, novels never collapse within a source), so both queries are covered separately.
 */
class MergedUnreadCountTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var repository: MergeGroupRepositoryImpl

    @BeforeEach
    fun setUp() {
        runTest {
            driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
            Database.Schema.create(driver).await()
            driver.execute(null, "PRAGMA foreign_keys=ON", 0).await()
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
            repository = MergeGroupRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    private fun mangaCounts(): Map<Long, Long> = database.chapter_match_keyQueries
        .mergedUnreadCounts()
        .executeAsList()
        .associate { it.groupId to it.unreadCount }

    private fun novelCounts(): Map<Long, Long> = database.chapter_match_keyQueries
        .mergedUnreadCountsNovel()
        .executeAsList()
        .associate { it.groupId to it.unreadCount }

    @Test
    fun `a chapter read on one source is not unread on the group`() = runTest {
        insertManga(1)
        insertManga(2)
        val group = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        // The same chapter 1 from both sources, read only on source 2.
        insertChapter(id = 10, mangaId = 1, number = 1.0, read = false, key = "1.0")
        insertChapter(id = 20, mangaId = 2, number = 1.0, read = true, key = "1.0")

        mangaCounts()[group] shouldBe null
    }

    @Test
    fun `a chapter unread on every source counts once, not once per source`() = runTest {
        insertManga(1)
        insertManga(2)
        val group = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        insertChapter(id = 10, mangaId = 1, number = 1.0, read = false, key = "1.0")
        insertChapter(id = 20, mangaId = 2, number = 1.0, read = false, key = "1.0")

        mangaCounts()[group] shouldBe 1L
    }

    @Test
    fun `a chapter only one source has still counts`() = runTest {
        insertManga(1)
        insertManga(2)
        val group = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        insertChapter(id = 10, mangaId = 1, number = 1.0, read = true, key = "1.0")
        insertChapter(id = 20, mangaId = 2, number = 1.0, read = true, key = "1.0")
        // Only source 2 carries chapter 2, unread.
        insertChapter(id = 21, mangaId = 2, number = 2.0, read = false, key = "2.0")

        mangaCounts()[group] shouldBe 1L
    }

    @Test
    fun `a chapter with no cross-source identity counts on its own`() = runTest {
        insertManga(1)
        insertManga(2)
        val group = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        // Null keys never dedup, so these are two distinct unread chapters, not one.
        insertChapter(id = 10, mangaId = 1, number = -1.0, read = false, key = null)
        insertChapter(id = 20, mangaId = 2, number = -1.0, read = false, key = null)

        mangaCounts()[group] shouldBe 2L
    }

    @Test
    fun `a fully read group is absent rather than zero`() = runTest {
        insertManga(1)
        insertManga(2)
        val group = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        insertChapter(id = 10, mangaId = 1, number = 1.0, read = true, key = "1.0")
        insertChapter(id = 20, mangaId = 2, number = 1.0, read = true, key = "1.0")

        mangaCounts().containsKey(group) shouldBe false
    }

    @Test
    fun `an excluded scanlator's chapters are ignored`() = runTest {
        insertManga(1)
        insertManga(2)
        val group = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        insertChapter(id = 10, mangaId = 1, number = 1.0, read = false, key = "1.0", scanlator = "bad")
        driver.execute(
            null,
            "INSERT INTO excluded_scanlators(manga_id, scanlator) VALUES (1, 'bad')",
            0,
        ).await()

        mangaCounts()[group] shouldBe null
    }

    @Test
    fun `manga collapses a source's own scanlator variants`() = runTest {
        insertManga(1)
        insertManga(2)
        val group = repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        // One source listing chapter 1 twice under different scanlators is still one chapter.
        insertChapter(id = 10, mangaId = 1, number = 1.0, read = false, key = "1.0", scanlator = "a")
        insertChapter(id = 11, mangaId = 1, number = 1.0, read = false, key = "1.0", scanlator = "b")

        mangaCounts()[group] shouldBe 1L
    }

    @Test
    fun `a novel source's repeated titles stay distinct chapters`() = runTest {
        insertNovel(1)
        insertNovel(2)
        val group = repository.createGroup(ContentType.NOVELS, listOf(1, 2))!!
        // Novels have no scanlator variants, so two rows normalising to the same title are two
        // different chapters (a title repeated across volumes). Source 2 carries one of them.
        insertNovelChapter(id = 10, novelId = 1, read = false, key = "t:prologue")
        insertNovelChapter(id = 11, novelId = 1, read = false, key = "t:prologue")
        insertNovelChapter(id = 20, novelId = 2, read = false, key = "t:prologue")

        mangaCounts().size shouldBe 0
        novelCounts()[group] shouldBe 2L
    }

    @Test
    fun `a novel chapter read on one source is not unread on the group`() = runTest {
        insertNovel(1)
        insertNovel(2)
        val group = repository.createGroup(ContentType.NOVELS, listOf(1, 2))!!
        insertNovelChapter(id = 10, novelId = 1, read = false, key = "t:prologue")
        insertNovelChapter(id = 20, novelId = 2, read = true, key = "t:prologue")

        novelCounts()[group] shouldBe null
    }

    @Test
    fun `novel occurrences pair up in order across sources`() = runTest {
        insertNovel(1)
        insertNovel(2)
        val group = repository.createGroup(ContentType.NOVELS, listOf(1, 2))!!
        // Each source has two "prologue" chapters; the first pairs with the first, the second with
        // the second. Reading both on source 2 clears both units.
        insertNovelChapter(id = 10, novelId = 1, read = false, key = "t:prologue")
        insertNovelChapter(id = 11, novelId = 1, read = false, key = "t:prologue")
        insertNovelChapter(id = 20, novelId = 2, read = true, key = "t:prologue")
        insertNovelChapter(id = 21, novelId = 2, read = true, key = "t:prologue")

        novelCounts()[group] shouldBe null
    }

    private fun staleMangaChapterIds(): List<Long> = database.chapter_match_keyQueries
        .staleMergedChapters()
        .executeAsList()
        .map { it.chapterId }

    @Test
    fun `reconciliation finds a merged chapter that has no key yet`() = runTest {
        insertManga(1)
        insertManga(2)
        repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        insertChapter(id = 10, mangaId = 1, number = 1.0, read = false, key = "1.0")
        insertChapter(id = 20, mangaId = 2, number = 1.0, read = false, key = null, writeKey = false)

        staleMangaChapterIds() shouldBe listOf(20L)
    }

    @Test
    fun `reconciliation finds a chapter whose number changed after its key was written`() = runTest {
        insertManga(1)
        insertManga(2)
        repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        insertChapter(id = 10, mangaId = 1, number = 1.0, read = false, key = "1.0")
        // The source renumbered it; the stored key was derived from the old number.
        driver.execute(null, "UPDATE chapters SET chapter_number = 2.0 WHERE _id = 10", 0).await()

        staleMangaChapterIds() shouldBe listOf(10L)
    }

    @Test
    fun `reconciliation ignores chapters of unmerged entries`() = runTest {
        insertManga(1)
        insertChapter(id = 10, mangaId = 1, number = 1.0, read = false, key = null, writeKey = false)

        staleMangaChapterIds().size shouldBe 0
    }

    @Test
    fun `reconciliation reports nothing when every key is current`() = runTest {
        insertManga(1)
        insertManga(2)
        repository.createGroup(ContentType.MANGA, listOf(1, 2))!!
        insertChapter(id = 10, mangaId = 1, number = 1.0, read = false, key = "1.0")
        insertChapter(id = 20, mangaId = 2, number = 1.0, read = false, key = "1.0")

        staleMangaChapterIds().size shouldBe 0
    }

    @Test
    fun `a newly merged entry's chapters show up as stale`() = runTest {
        insertManga(1)
        insertManga(2)
        // Chapters exist before the merge, so no keys were ever needed for them.
        insertChapter(id = 10, mangaId = 1, number = 1.0, read = false, key = null, writeKey = false)
        insertChapter(id = 20, mangaId = 2, number = 1.0, read = false, key = null, writeKey = false)
        staleMangaChapterIds().size shouldBe 0

        repository.createGroup(ContentType.MANGA, listOf(1, 2))!!

        staleMangaChapterIds() shouldBe listOf(10L, 20L)
    }

    @Test
    fun `an unmerged entry produces no group row`() = runTest {
        insertManga(1)
        insertChapter(id = 10, mangaId = 1, number = 1.0, read = false, key = "1.0")

        mangaCounts().size shouldBe 0
    }

    // Minimal valid parent rows: only the NOT NULL columns without a default need values.
    private suspend fun insertManga(id: Long) {
        driver.execute(
            null,
            "INSERT INTO mangas(_id, source, url, title, status, favorite, initialized, viewer, " +
                "chapter_flags, cover_last_modified, date_added) " +
                "VALUES ($id, 1, 'm-url-$id', 'title', 0, 0, 0, 0, 0, 0, 0)",
            0,
        ).await()
    }

    private suspend fun insertNovel(id: Long) {
        driver.execute(
            null,
            "INSERT INTO novels(_id, source, url, title, status, favorite, initialized, chapter_flags) " +
                "VALUES ($id, 'src', 'n-url-$id', 'title', 0, 0, 0, 0)",
            0,
        ).await()
    }

    private suspend fun insertChapter(
        id: Long,
        mangaId: Long,
        number: Double,
        read: Boolean,
        key: String?,
        scanlator: String? = null,
        writeKey: Boolean = true,
    ) {
        val scanlatorValue = scanlator?.let { "'$it'" } ?: "NULL"
        driver.execute(
            null,
            "INSERT INTO chapters(_id, manga_id, url, name, scanlator, read, bookmark, " +
                "last_page_read, chapter_number, source_order, date_fetch, date_upload) " +
                "VALUES ($id, $mangaId, 'c-url-$id', 'name', $scanlatorValue, ${if (read) 1 else 0}, " +
                "0, 0, $number, 0, 0, 0)",
            0,
        ).await()
        if (writeKey) {
            val keyValue = key?.let { "'$it'" } ?: "NULL"
            driver.execute(
                null,
                "INSERT INTO chapter_match_key(chapter_id, match_key, derived_number) " +
                    "VALUES ($id, $keyValue, $number)",
                0,
            ).await()
        }
    }

    private suspend fun insertNovelChapter(
        id: Long,
        novelId: Long,
        read: Boolean,
        key: String?,
    ) {
        driver.execute(
            null,
            "INSERT INTO novel_chapters(_id, novel_id, url, name, read, bookmark, chapter_number, " +
                "source_order, date_fetch, date_upload) " +
                "VALUES ($id, $novelId, 'c-url-$id', 'name', ${if (read) 1 else 0}, 0, 0, 0, 0, 0)",
            0,
        ).await()
        val keyValue = key?.let { "'$it'" } ?: "NULL"
        driver.execute(
            null,
            "INSERT INTO novel_chapter_match_key(chapter_id, match_key, derived_name, derived_number) " +
                "VALUES ($id, $keyValue, 'name', 0)",
            0,
        ).await()
    }
}
