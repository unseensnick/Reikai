package reikai.data.novel

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelUpdate
import reikai.novel.host.ChapterItem
import reikai.novel.host.NovelItem
import reikai.novel.host.SourceNovel
import reikai.novel.source.NovelExtensionFormat
import reikai.novel.source.NovelFilterState
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelListing
import reikai.novel.source.NovelSource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.InMemoryPreferenceStore.InMemoryPreference
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
import tachiyomi.domain.library.service.LibraryPreferences

/** A library refresh over the real SQL, with the plugin host faked by [PagedSource]. */
class NovelRefreshTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var database: Database
    private lateinit var novels: RecordingNovelRepository
    private lateinit var chapters: NovelChapterRepositoryImpl

    private val libraryPreferences = LibraryPreferences(
        InMemoryPreferenceStore(
            sequenceOf(
                InMemoryPreference(
                    "mark_duplicate_read_chapter_read",
                    setOf(LibraryPreferences.MARK_DUPLICATE_CHAPTER_READ_NEW),
                    emptySet(),
                ),
            ),
        ),
    )

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
            novels = RecordingNovelRepository(NovelRepositoryImpl(database))
            chapters = NovelChapterRepositoryImpl(database)
        }
    }

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    private suspend fun storedNovel(nextUpdate: Long = 0L, totalPages: Long = 1L): Novel {
        val id = novels.insert(
            Novel.create().copy(
                source = "src",
                url = "/novel",
                title = "Novel",
                favorite = true,
                nextUpdate = nextUpdate,
                totalPages = totalPages,
            ),
        )!!
        return novels.getById(id)!!
    }

    private suspend fun storedChapter(novel: Novel, url: String, number: Double, read: Boolean, page: String = "") {
        chapters.insert(
            NovelChapter(
                id = -1L, novelId = novel.id, url = url, name = "Chapter $number", read = read, bookmark = false,
                lastTextProgress = 0L, chapterNumber = number, sourceOrder = 0L, dateFetch = 0L, dateUpload = 0L,
                page = page,
            ),
        )
    }

    private suspend fun refresh(novel: Novel, source: NovelSource, manualFetch: Boolean = false) =
        refreshNovelFromSource(
            novel,
            source,
            chapters,
            novels,
            database,
            libraryPreferences,
            manualFetch = manualFetch,
            fetchWindow = 1_000L to 2_000L,
        )

    @Test
    fun `a new copy of a read chapter is not reported as new`() = runTest {
        val novel = storedNovel()
        storedChapter(novel, "/c/12-a", 12.0, read = true)
        val source = PagedSource(listOf(chapter("/c/12-a", 12.0), chapter("/c/12-b", 12.0)))

        refresh(novel, source).newChapters shouldBe emptyList()
    }

    @Test
    fun `a chapter re-listed at a new address is not reported as new`() = runTest {
        val novel = storedNovel()
        // Unread, so the duplicate-read rule cannot be what holds it back.
        storedChapter(novel, "/c/12-a", 12.0, read = false)
        // A later row, so the re-listed one cannot take the removed row's id back.
        storedChapter(novel, "/c/13", 13.0, read = false)
        val source = PagedSource(listOf(chapter("/c/13", 13.0), chapter("/c/12-b", 12.0)))

        refresh(novel, source).newChapters shouldBe emptyList()
    }

    @Test
    fun `a new copy of a chapter read on another page is not reported as new`() = runTest {
        val novel = storedNovel()
        storedChapter(novel, "/c/12-a", 12.0, read = true, page = "1")
        val source = PagedSource(listOf(chapter("/c/12-a", 12.0)), mapOf("2" to listOf(chapter("/c/12-b", 12.0))))

        refresh(novel, source).newChapters shouldBe emptyList()
    }

    @Test
    fun `new chapters on every walked page are reported`() = runTest {
        val source = PagedSource(
            listOf(chapter("/c/1", 1.0)),
            mapOf("2" to listOf(chapter("/c/2", 2.0)), "3" to listOf(chapter("/c/3", 3.0))),
        )

        refresh(storedNovel(), source).newChapters.map { it.url } shouldBe listOf("/c/1", "/c/2", "/c/3")
    }

    @Test
    fun `a paged refresh predicts the next update once`() = runTest {
        val source = PagedSource(
            listOf(chapter("/c/1", 1.0)),
            mapOf("2" to listOf(chapter("/c/2", 2.0)), "3" to listOf(chapter("/c/3", 3.0))),
        )

        refresh(storedNovel(nextUpdate = 1L), source, manualFetch = true)

        novels.predictions shouldBe 1
    }

    /**
     * A newest-first paged source after one release: page 1 was 100..76 and page 2 75..51, and chapter 101
     * pushed 76 onto page 2. The third page keeps page 2 out of this refresh's walk, as it is for any middle
     * page, so 76 reaches page 2 only through a later page sync.
     */
    private suspend fun novelAfterARelease(): Pair<Novel, PagedSource> {
        val novel = storedNovel(totalPages = 3L)
        (100 downTo 76).forEach { storedChapter(novel, "/c/$it", it.toDouble(), read = it == 76, page = "1") }
        (75 downTo 51).forEach { storedChapter(novel, "/c/$it", it.toDouble(), read = false, page = "2") }
        val source = PagedSource(
            (101 downTo 77).map { chapter("/c/$it", it.toDouble()) },
            mapOf("2" to (76 downTo 52).map { chapter("/c/$it", it.toDouble()) }, "3" to emptyList()),
        )
        return novel to source
    }

    private suspend fun rowsFor(novel: Novel, url: String) = chapters.getByNovelId(novel.id).filter { it.url == url }

    private suspend fun syncPage(novel: Novel, source: PagedSource, page: String) =
        syncPage(novel, source.parsePage(novel.url, page).chapters!!, page)

    private suspend fun syncPage(novel: Novel, items: List<ChapterItem>, page: String) = syncChaptersWithNovelSource(
        items,
        novels.getById(novel.id)!!,
        chapters,
        novels,
        database,
        libraryPreferences,
        page = page,
    )

    @Test
    fun `a chapter pushed off the first page keeps its row and read state`() = runTest {
        val (novel, source) = novelAfterARelease()
        val before = rowsFor(novel, "/c/76").map { it.id to it.read }

        refresh(novel, source)

        rowsFor(novel, "/c/76").map { it.id to it.read } shouldBe before
    }

    @Test
    fun `a chapter pushed onto the next page is not reported as new there`() = runTest {
        val (novel, source) = novelAfterARelease()
        refresh(novel, source)

        syncPage(novel, source, "2").newChapters shouldBe emptyList()
    }

    @Test
    fun `a chapter pushed onto the next page moves there once and stays read`() = runTest {
        val (novel, source) = novelAfterARelease()
        val id = rowsFor(novel, "/c/76").single().id
        refresh(novel, source)

        syncPage(novel, source, "2")

        rowsFor(novel, "/c/76").map { Triple(it.id, it.read, it.page) } shouldBe listOf(Triple(id, true, "2"))
    }

    @Test
    fun `a chapter stored on two pages keeps the copy that was read`() = runTest {
        val novel = storedNovel(totalPages = 2L)
        storedChapter(novel, "/c/5", 5.0, read = false, page = "1")
        storedChapter(novel, "/c/5", 5.0, read = true, page = "2")

        syncPage(novel, listOf(chapter("/c/5", 5.0)), "1")

        rowsFor(novel, "/c/5").map { it.read to it.page } shouldBe listOf(true to "1")
    }

    private fun chapter(path: String, number: Double) = ChapterItem(
        name = "Chapter $number",
        path = path,
        chapterNumber = number,
    )

    /** Counts the writes that store a next-update prediction. */
    private class RecordingNovelRepository(private val real: NovelRepository) : NovelRepository by real {
        var predictions = 0

        override suspend fun update(update: NovelUpdate): Boolean {
            if (update.nextUpdate != null) predictions++
            return real.update(update)
        }
    }

    /** Page 1 comes from [parseNovel]; the rest from [parsePage]. More than one page makes it a paged source. */
    private class PagedSource(
        private val firstPage: List<ChapterItem>,
        private val otherPages: Map<String, List<ChapterItem>> = emptyMap(),
    ) : NovelSource {
        override val id = "src"
        override val name = "Source"
        override val version = "1.0.0"
        override val site = "https://src.example"
        override val lang = "en"
        override val iconUrl: String? = null
        override val format = NovelExtensionFormat.JS
        override val extensionName = name
        override val contentWarning = ContentWarning.SAFE

        override suspend fun parseNovel(novelPath: String) =
            SourceNovel(path = novelPath, name = "Novel", chapters = firstPage, totalPages = otherPages.size + 1)

        override suspend fun parsePage(novelPath: String, page: String) =
            SourceNovel(path = novelPath, chapters = otherPages[page])

        override suspend fun parseChapter(chapterPath: String): String = unused()

        override suspend fun browse(listing: NovelListing, page: Int, filters: NovelFilterState?): NovelItemsPage =
            unused()

        override suspend fun search(query: String, page: Int, filters: NovelFilterState?): NovelItemsPage = unused()

        private fun unused(): Nothing = throw UnsupportedOperationException("Not part of a refresh")
    }
}
