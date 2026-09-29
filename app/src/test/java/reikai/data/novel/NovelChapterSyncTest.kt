package reikai.data.novel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
import tachiyomi.domain.chapter.model.NoChaptersException
import tachiyomi.domain.library.service.LibraryPreferences

/** The sync's rules over a stubbed repository; the one-transaction write is [NovelChapterUpdateFromRemoteTest]'s. */
class NovelChapterSyncTest {

    private data class InsertedRow(
        val url: String,
        val read: Boolean,
        val bookmark: Boolean,
        val dateFetch: Long,
        val name: String,
        val dateUpload: Long,
    )

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun dbChapter(
        url: String,
        number: Double,
        read: Boolean = false,
        bookmark: Boolean = false,
        dateFetch: Long = 0L,
        id: Long = 1L,
        dateUpload: Long = 0L,
    ) = NovelChapter(
        id = id, novelId = 1L, url = url, name = "name", read = read, bookmark = bookmark,
        lastTextProgress = 0L, chapterNumber = number, sourceOrder = 0L, dateFetch = dateFetch,
        dateUpload = dateUpload, page = "",
    )

    private fun srcItem(url: String, number: Double, name: String = "name", releaseTime: String? = null) =
        ChapterItem(name = name, path = url, chapterNumber = number, releaseTime = releaseTime)

    private class Synced(
        val result: NovelChapterSyncResult,
        val inserted: List<InsertedRow>,
        val changed: List<NovelChapter>,
    )

    private suspend fun sync(
        db: List<NovelChapter>,
        source: List<ChapterItem>,
        downloadManager: NovelDownloadManager? = null,
        markDuplicates: Set<String> = setOf(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW),
    ): Synced {
        val inserted = mutableListOf<InsertedRow>()
        val changed = mutableListOf<NovelChapter>()
        val novelChapterRepository = mockk<NovelChapterRepository>(relaxed = true) {
            coEvery { getByNovelId(1L) } returns db
            coEvery { updateFromRemote(any(), any(), any()) } coAnswers {
                val added = secondArg<List<NovelChapter>>()
                changed += thirdArg<List<NovelChapter>>()
                added.forEach {
                    inserted.add(InsertedRow(it.url, it.read, it.bookmark, it.dateFetch, it.name, it.dateUpload))
                }
                added.mapIndexed { i, chapter -> chapter.copy(id = 100L + i) }
            }
        }
        val novelRepository = mockk<NovelRepository>(relaxed = true)
        val novel = Novel.create().copy(id = 1L, title = "Test")

        val result = syncChaptersWithNovelSource(
            source,
            novel,
            novelChapterRepository,
            novelRepository,
            LibraryPreferences(
                InMemoryPreferenceStore(
                    sequenceOf(InMemoryPreference("mark_duplicate_read_chapter_read", markDuplicates, emptySet())),
                ),
            ),
            novelDownloadManager = downloadManager,
        )
        return Synced(result, inserted, changed)
    }

    @Test
    fun `a source that stops dating a chapter leaves the stored row alone`() = runTest {
        val db = listOf(dbChapter("/c/5", number = 5.0, dateUpload = 5_000L))

        sync(db, listOf(srcItem("/c/5", number = 5.0))).result.changed shouldBe false
    }

    @Test
    fun `a re-titled chapter the source stopped dating keeps its stored date`() = runTest {
        val db = listOf(dbChapter("/c/5", number = 5.0, dateUpload = 5_000L))

        sync(db, listOf(srcItem("/c/5", number = 5.0, name = "Renamed"))).changed.single().dateUpload shouldBe 5_000L
    }

    @Test
    fun `a chapter name drops the novel's title in front`() = runTest {
        val source = listOf(srcItem("/c/1", number = 1.0, name = "Test - Chapter 1"))

        sync(emptyList(), source).inserted.single().name shouldBe "Chapter 1"
    }

    @Test
    fun `an undated new chapter takes the date of the dated one listed above it`() = runTest {
        val source = listOf(
            srcItem("/c/2", number = 2.0, releaseTime = "2024-01-02T00:00:00Z"),
            srcItem("/c/1", number = 1.0),
        )

        sync(emptyList(), source).inserted.map { it.dateUpload }.distinct() shouldBe listOf(1_704_153_600_000L)
    }

    @Test
    fun `a source that lists no chapter fails with NoChaptersException`() = runTest {
        shouldThrow<NoChaptersException> { sync(emptyList(), emptyList()) }
    }

    @Test
    fun `a new chapter matching a read chapter's number is marked read`() = runTest {
        val db = listOf(dbChapter("/c/5-a", number = 5.0, read = true))
        val source = listOf(srcItem("/c/5-a", number = 5.0), srcItem("/c/5-b", number = 5.0))

        sync(db, source).inserted.single().read shouldBe true
    }

    @Test
    fun `a new chapter marked read as a duplicate does not surface as new`() = runTest {
        val db = listOf(dbChapter("/c/5-a", number = 5.0, read = true))
        val source = listOf(srcItem("/c/5-a", number = 5.0), srcItem("/c/5-b", number = 5.0))

        sync(db, source).result.newChapters shouldBe emptyList()
    }

    @Test
    fun `a new duplicate chapter stays unread when the setting is off`() = runTest {
        val db = listOf(dbChapter("/c/5-a", number = 5.0, read = true))
        val source = listOf(srcItem("/c/5-a", number = 5.0), srcItem("/c/5-b", number = 5.0))

        sync(db, source, markDuplicates = emptySet()).inserted.single().read shouldBe false
    }

    @Test
    fun `a new unnumbered chapter stays unread when an unnumbered chapter was read`() = runTest {
        val db = listOf(dbChapter("/prologue", number = -1.0, read = true))
        val source = listOf(srcItem("/prologue", number = -1.0), srcItem("/afterword", number = -1.0))

        sync(db, source).inserted.single().read shouldBe false
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

        sync(db, source).result.newChapters shouldBe emptyList()
    }

    @Test
    fun `a genuinely new chapter surfaces as new`() = runTest {
        val source = listOf(srcItem("/c/1", number = 1.0))

        sync(emptyList(), source).result.newChapters.map { it.url } shouldBe listOf("/c/1")
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
