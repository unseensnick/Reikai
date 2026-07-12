package reikai.data.novel

import app.cash.sqldelight.SuspendingTransactionWithoutReturn
import app.cash.sqldelight.async.coroutines.awaitAsOne
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadManager
import reikai.novel.host.ChapterItem
import tachiyomi.data.Database

/**
 * Reconciliation of a freshly-parsed novel chapter list against the stored rows. Covers the three
 * behaviours a reader would notice if they broke: a re-added chapter (same number, new url) keeps its
 * read/bookmark state instead of resurfacing as unread; trailing-slash url variants are treated as
 * distinct (exact-match dedup only); and fetch dates stagger so list order stays stable.
 *
 * The writes happen inside a suspend `database.transaction`, so the test runs that body inline and
 * captures the insert arguments; selectLastInsertedRowId().awaitAsOne() is stubbed via the async
 * extension facade.
 */
class NovelChapterSyncTest {

    private data class InsertedRow(val url: String, val read: Boolean, val bookmark: Boolean, val dateFetch: Long)

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun dbChapter(
        url: String,
        number: Double,
        read: Boolean = false,
        bookmark: Boolean = false,
        dateFetch: Long = 0L,
        id: Long = 1L,
    ) = NovelChapter(
        id = id, novelId = 1L, url = url, name = "name", read = read, bookmark = bookmark,
        lastTextProgress = 0L, chapterNumber = number, sourceOrder = 0L, dateFetch = dateFetch,
        dateUpload = 0L, page = "",
    )

    private fun srcItem(url: String, number: Double, name: String = "name") =
        ChapterItem(name = name, path = url, chapterNumber = number)

    private class Synced(
        val result: Pair<List<NovelChapter>, List<NovelChapter>>,
        val inserted: List<InsertedRow>,
    )

    private suspend fun sync(
        db: List<NovelChapter>,
        source: List<ChapterItem>,
        downloadManager: NovelDownloadManager? = null,
    ): Synced {
        mockkStatic("app.cash.sqldelight.async.coroutines.QueryExtensionsKt")
        val inserted = mutableListOf<InsertedRow>()
        val database = mockk<Database>(relaxed = true) {
            coEvery { transaction(any(), any()) } coAnswers {
                secondArg<suspend SuspendingTransactionWithoutReturn.() -> Unit>().invoke(mockk(relaxed = true))
            }
            coEvery {
                novel_chaptersQueries.insert(
                    any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                )
            } coAnswers {
                inserted.add(InsertedRow(url = arg(1), read = arg(3), bookmark = arg(4), dateFetch = arg(8)))
                0L
            }
            coEvery { novel_chaptersQueries.selectLastInsertedRowId().awaitAsOne() } returns 100L
        }
        val novelChapterRepository = mockk<NovelChapterRepository>(relaxed = true) {
            coEvery { getByNovelId(1L) } returns db
        }
        val novelRepository = mockk<NovelRepository>(relaxed = true)
        val novel = Novel.create().copy(id = 1L, title = "Test")

        val result = syncChaptersWithNovelSource(
            source,
            novel,
            novelChapterRepository,
            novelRepository,
            database,
            novelDownloadManager = downloadManager,
        )
        return Synced(result, inserted)
    }

    @Test
    fun `a re-added chapter inherits the deleted twin's read and bookmark state`() = runTest {
        val db = listOf(dbChapter("/c/5-old", number = 5.0, read = true, bookmark = true))
        val source = listOf(srcItem("/c/5-new", number = 5.0))

        val row = sync(db, source).inserted.single()

        (row.read to row.bookmark) shouldBe (true to true)
    }

    @Test
    fun `a re-added chapter reuses the deleted twin's fetch date`() = runTest {
        val db = listOf(dbChapter("/c/5-old", number = 5.0, read = true, dateFetch = 1000L))
        val source = listOf(srcItem("/c/5-new", number = 5.0))

        sync(db, source).inserted.single().dateFetch shouldBe 1000L
    }

    @Test
    fun `a re-added chapter does not resurface as new`() = runTest {
        val db = listOf(dbChapter("/c/5-old", number = 5.0, read = true))
        val source = listOf(srcItem("/c/5-new", number = 5.0))

        sync(db, source).result.first shouldBe emptyList()
    }

    @Test
    fun `a genuinely new chapter surfaces as new`() = runTest {
        val source = listOf(srcItem("/c/1", number = 1.0))

        sync(emptyList(), source).result.first.map { it.url } shouldBe listOf("/c/1")
    }

    @Test
    fun `a genuinely new chapter is inserted unread`() = runTest {
        val source = listOf(srcItem("/c/1", number = 1.0))

        sync(emptyList(), source).inserted.single().read shouldBe false
    }

    @Test
    fun `trailing-slash url variants are kept as distinct chapters`() = runTest {
        // Dedup is exact-string, so "/c/5" and "/c/5/" are two chapters, not one.
        val source = listOf(srcItem("/c/5", number = 5.0), srcItem("/c/5/", number = 5.0))

        sync(emptyList(), source).inserted.map { it.url } shouldContainExactlyInAnyOrder listOf("/c/5", "/c/5/")
    }

    @Test
    fun `a re-titled chapter triggers a download rename so its stable-name file follows`() = runTest {
        // Same url, new name: a toChange whose downloaded file must be relocated (Option 1 rename-on-sync).
        val old = dbChapter("/c/5", number = 5.0) // name = "name"
        val source = listOf(srcItem("/c/5", number = 5.0, name = "Renamed"))
        val downloadManager = mockk<NovelDownloadManager>(relaxed = true)

        sync(listOf(old), source, downloadManager)

        coVerify {
            downloadManager.renameChapter(
                match { it.id == 1L },
                match { it.url == "/c/5" && it.name == "name" },
                match { it.url == "/c/5" && it.name == "Renamed" },
            )
        }
    }

    @Test
    fun `an unchanged chapter title does not trigger a download rename`() = runTest {
        val old = dbChapter("/c/5", number = 5.0) // name = "name"
        val source = listOf(srcItem("/c/5", number = 5.0)) // same name
        val downloadManager = mockk<NovelDownloadManager>(relaxed = true)

        sync(listOf(old), source, downloadManager)

        coVerify(exactly = 0) { downloadManager.renameChapter(any(), any(), any()) }
    }

    @Test
    fun `fetch dates stagger strictly downward in source order`() = runTest {
        // Sources return newest-first, so earlier rows get the higher date_fetch to preserve order.
        val source = listOf(srcItem("/c/3", 3.0), srcItem("/c/2", 2.0), srcItem("/c/1", 1.0))

        val dates = sync(emptyList(), source).inserted.map { it.dateFetch }

        dates.zipWithNext().all { (a, b) -> a > b } shouldBe true
    }
}
