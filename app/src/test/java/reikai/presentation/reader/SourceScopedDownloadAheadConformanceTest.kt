package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.novel.model.NovelChapter
import reikai.domain.novel.model.NovelChapterFlags
import tachiyomi.domain.manga.model.Manga
import java.util.concurrent.ConcurrentHashMap

/**
 * A reader opened through one source of a merged series (a source chip, Updates) downloads ahead in the
 * group's shared chapter order, its settings owner's, as it pages. The group is OWNER, sorted by chapter
 * number, then SIBLING, sorted by source order, which lists chapters 1, 3, 4, 2, 5. Reading SIBLING's
 * chapter 1 with chapter 2 on disk and two chapters ahead, the one left to fetch is chapter 3.
 */
class SourceScopedDownloadAheadConformanceTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a source-scoped reader on a sibling downloads ahead in the owner's chapter order`(
        probe: SourceScopedAheadProbe,
    ) = runTest {
        probe.fetchedAhead(this) shouldBe setOf(3.0)
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaSourceScopedAhead(), NovelSourceScopedAhead())
    }
}

/** SIBLING's chapters by number, with the source order that lists them 1, 3, 4, 2, 5. */
private val SIBLING_ORDER = listOf(1.0 to 0L, 3.0 to 1L, 4.0 to 2L, 2.0 to 3L, 5.0 to 4L)

interface SourceScopedAheadProbe {

    /** The chapter numbers download-ahead hands the downloader beyond the one already on disk. */
    suspend fun fetchedAhead(scope: TestScope): Set<Double>
}

class MangaSourceScopedAhead : SourceScopedAheadProbe {

    override fun toString() = "manga"

    override suspend fun fetchedAhead(scope: TestScope) = MangaReaderViewModelHarness.create().use { harness ->
        val owner = harness.manga(1L, 100L, "Owner", chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
        val sibling = harness.manga(2L, 200L, "Sibling", chapterFlags = Manga.CHAPTER_SORTING_SOURCE)
        // A manga source lists newest first, so the chapter its order reads first carries the highest.
        val chapters = SIBLING_ORDER.map { (number, listed) ->
            harness.chapter(20L + number.toLong(), sibling, number, order = 100L - listed)
        }
        val numberOf = chapters.associate { it.id to it.chapterNumber }
        val group = MergedChapterProvider.Group(
            mangaById = mapOf(owner.id to owner, sibling.id to sibling),
            chapters = chapters,
            sourceNameByMangaId = mapOf(owner.id to "Owner", sibling.id to "Sibling"),
        )
        val queued = ConcurrentHashMap.newKeySet<Long>()
        harness.open(
            sibling,
            chapterId = 21L,
            group = group,
            preferences = mapOf("auto_download_while_reading" to 2),
            onDisk = setOf(21L, 22L),
            sourceScoped = true,
            onDownload = { downloaded -> downloaded.forEach { queued += it.id } },
        ) { model, state ->
            val current = state.viewerChapters!!.currChapter
            val pages = loadedPages(current, 2)
            // Download-ahead only runs while the chapter being read is itself read from disk.
            current.pageLoader = mockk<DownloadPageLoader>(relaxed = true)
            model.onPageSelected(pages[1])
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(3_000) { while (queued.isEmpty()) delay(20) }
                // Anything else a wrong order would queue lands in the same pass.
                delay(300)
            }
            (queued - 22L).mapTo(HashSet()) { numberOf.getValue(it) }
        }
    }
}

class NovelSourceScopedAhead : SourceScopedAheadProbe {

    override fun toString() = "novel"

    override suspend fun fetchedAhead(scope: TestScope): Set<Double> =
        NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
            val owner = harness.novel(harness.source("owner"))
            val sibling = harness.novel(harness.source("sibling"))
            harness.updateNovel(owner) {
                chapterFlags = NovelChapterFlags.SORTING_NUMBER or NovelChapterFlags.SORT_LOCAL
            }
            harness.updateNovel(sibling) {
                chapterFlags = NovelChapterFlags.SORTING_SOURCE or NovelChapterFlags.SORT_LOCAL
            }
            val chapters = SIBLING_ORDER.associate { (number, listed) ->
                number to harness.chapter(sibling, number, sourceOrder = listed)
            }
            harness.download(chapters.getValue(2.0), "<p>On disk</p>")
            harness.merge(owner, sibling)
            val numberOf = chapters.entries.associate { (number, seeded) -> seeded.id to number }
            val queued = ConcurrentHashMap.newKeySet<Double>()
            coEvery { harness.downloadManager.downloadChapters(any()) } answers {
                firstArg<List<NovelChapter>>().forEach { queued += numberOf.getValue(it.id) }
            }
            harness.novelPreferences.autoDownloadWhileReading().set(2)
            harness.open(sibling, chapters.getValue(1.0).id, sourceScoped = true)
            scope.advanceUntilIdle()
            queued - 2.0
        }
}
