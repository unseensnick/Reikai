package reikai.domain.novel.interactor

import android.content.Context
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import eu.kanade.tachiyomi.data.cache.CoverCache
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.data.novel.NovelChapterRepositoryImpl
import reikai.data.novel.NovelRepositoryImpl
import reikai.domain.merge.ReconcileMergedChapters
import reikai.domain.novel.model.Novel
import reikai.novel.host.ChapterItem
import reikai.novel.host.SourceNovel
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.data.Database
import tachiyomi.data.DatabaseBindings
import tachiyomi.domain.library.service.LibraryPreferences
import java.io.File

class RepairNovelDetailsTest {

    private fun novel(id: Long, title: String, source: String, url: String, author: String? = "Author") =
        Novel.create().copy(id = id, title = title, source = source, url = url, author = author, favoriteAt = 0L)

    @Test
    fun `same source, title and author at different urls flags both`() {
        val victim = novel(2, "BTTH", "novelfire", "book/catastrophic-necromancer")
        val donor = novel(1, "BTTH", "novelfire", "book/btth")

        RepairNovelDetails.findSuspects(listOf(donor, victim))
            .map { it.id } shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `the same title on different sources is normal and is not flagged`() {
        val a = novel(1, "BTTH", "novelfire", "book/btth")
        val b = novel(2, "BTTH", "novelarrow", "novel/btth")

        RepairNovelDetails.findSuspects(listOf(a, b)) shouldBe emptyList()
    }

    @Test
    fun `a healthy library flags nothing`() {
        val a = novel(1, "BTTH", "novelfire", "book/btth")
        val b = novel(2, "Catastrophic necromancer", "novelfire", "book/catastrophic-necromancer")

        RepairNovelDetails.findSuspects(listOf(a, b)) shouldBe emptyList()
    }

    @Test
    fun `title matching ignores case and surrounding space`() {
        val a = novel(1, "BTTH", "novelfire", "book/btth")
        val b = novel(2, "  btth ", "novelfire", "book/other")

        RepairNovelDetails.findSuspects(listOf(a, b)).map { it.id } shouldContainExactlyInAnyOrder listOf(1L, 2L)
    }

    @Test
    fun `the same title by different authors is not flagged`() {
        // A user-generated source (AO3 and the like) really does host different works under one title.
        val a = novel(1, "Beginning After the End", "archiveofourown", "works/41709720", author = "louwhose")
        val b = novel(2, "Beginning after the End", "archiveofourown", "works/36363133", author = "orphan_account")

        RepairNovelDetails.findSuspects(listOf(a, b)) shouldBe emptyList()
    }

    @Test
    fun `blank titles are never grouped together`() {
        val a = novel(1, "", "novelfire", "book/a")
        val b = novel(2, "", "novelfire", "book/b")

        RepairNovelDetails.findSuspects(listOf(a, b)) shouldBe emptyList()
    }

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

    @TempDir
    lateinit var cacheRoot: File

    @AfterEach
    fun tearDown() {
        driver.close()
    }

    /**
     * The title is the field the mix-up corrupted, so the repair takes the source's even with library
     * titles left to the user, which is the default. A refresh that kept it would count the novel as
     * repaired, and once its author is fixed no later run would flag it again.
     */
    @Test
    fun `a library novel wearing a neighbour's title gets its own back`() = runTest {
        repaired(VICTIM).title shouldBe "Victim"
    }

    /**
     * The source sends no author, as a plugin whose selector misses the page's tag does. An ordinary refresh
     * keeps the stored one then, but here that is the neighbour's, so it goes.
     */
    @Test
    fun `a repaired novel keeps none of its neighbour's details its source leaves out`() = runTest {
        repaired(VICTIM).author shouldBe null
    }

    /** The scan cannot tell the two apart, so the neighbour is refetched too, and its details are its own. */
    @Test
    fun `the neighbour whose details were copied keeps an author its source leaves out`() = runTest {
        repaired(DONOR).author shouldBe "Donor author"
    }

    @Test
    fun `the neighbour whose details were copied keeps a description its source leaves out`() = runTest {
        repaired(DONOR).description shouldBe "Donor synopsis"
    }

    /** A source naming no title proves nothing either way, so nothing is cleared. */
    @Test
    fun `a novel its source names no title for keeps its details`() = runTest {
        repaired(VICTIM, titles = mapOf(DONOR to "Donor")).author shouldBe "Donor author"
    }

    /**
     * Two library novels on one source wearing the donor's details, repaired against a source that names each by
     * [titles] and sends no author or description, as the plugin behind the reported case does. Returns the novel
     * stored at [url] afterwards.
     */
    private suspend fun repaired(url: String, titles: Map<String, String> = TITLES): Novel {
        Database.Schema.create(driver).await()
        val database = DatabaseBindings.providesDatabase(driver)
        val novels = NovelRepositoryImpl(database)
        val donor = Novel.create().copy(
            source = "src",
            url = DONOR,
            title = "Donor",
            author = "Donor author",
            description = "Donor synopsis",
            favoriteAt = 0L,
        )
        novels.insert(donor)
        novels.insert(donor.copy(url = VICTIM))
        val source = mockk<NovelSource> {
            every { id } returns "src"
            coEvery { parseNovel(any()) } answers {
                val path = firstArg<String>()
                SourceNovel(path = path, name = titles[path], chapters = listOf(ChapterItem("Chapter 1", "$path/1")))
            }
        }
        val context = mockk<Context> {
            every { getExternalFilesDir(any()) } answers { File(cacheRoot, firstArg<String>()).apply { mkdirs() } }
        }
        val repair = RepairNovelDetails(
            novelRepository = novels,
            novelChapterRepository = NovelChapterRepositoryImpl(database),
            sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get("src") } returns source },
            downloadManager = { mockk(relaxed = true) },
            libraryPreferences = LibraryPreferences(InMemoryPreferenceStore()),
            coverCache = CoverCache(context),
            reconcileMergedChapters = ReconcileMergedChapters(
                repository = mockk(relaxed = true),
                stitchers = emptySet(),
            ),
        )

        repair.await()
        return novels.getByUrlAndSource(url, "src")!!
    }

    private companion object {
        const val DONOR = "/donor"
        const val VICTIM = "/victim"
        val TITLES = mapOf(DONOR to "Donor", VICTIM to "Victim")
    }
}
