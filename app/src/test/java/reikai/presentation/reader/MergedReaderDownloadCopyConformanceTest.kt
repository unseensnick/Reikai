package reikai.presentation.reader

import eu.kanade.presentation.manga.components.ChapterDownloadAction
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
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.renderStoredStitch
import reikai.domain.novel.model.NovelChapter
import java.util.concurrent.ConcurrentHashMap

/**
 * Which copy the reader downloads in a merged series' group scope, pinned once over both readers. The
 * group is a member whose source is gone (M, ranked first, so its copies are the ones shown) and an
 * installed one (A). Chapters 1 to 3 are on both, chapter 4 only on M, and chapters 1 and 2 are read
 * from A's copies on disk. A download of a chapter shown from M fetches A's copy, or nothing.
 */
class MergedReaderDownloadCopyConformanceTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the sheet's download of a chapter shown from a missing source fetches the installed copy`(
        probe: ReaderDownloadProbe,
    ) = runTest {
        probe.sheetStart(this, ReaderCopy.M3) shouldBe setOf(ReaderCopy.A3)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `the sheet's download of a chapter only a missing source holds fetches nothing`(probe: ReaderDownloadProbe) =
        runTest {
            probe.sheetStart(this, ReaderCopy.M4) shouldBe emptySet()
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `download-ahead fetches the installed copy of each chapter shown from a missing source`(
        probe: ReaderDownloadProbe,
    ) = runTest {
        probe.downloadAhead(this) shouldBe setOf(ReaderCopy.A3)
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaDownloadProbe(), NovelDownloadProbe())
    }
}

/** The group's copies by role: M's copy of chapter 1 to 4, and A's of 1 to 3. */
enum class ReaderCopy { M1, M2, M3, M4, A1, A2, A3 }

/** One content type's reader opened in group scope on A's copy of chapter 1. */
interface ReaderDownloadProbe {

    /** The copies the downloader is handed when the chapter sheet starts [row]'s download. */
    suspend fun sheetStart(scope: TestScope, row: ReaderCopy): Set<ReaderCopy>

    /** The copies download-ahead hands the downloader from chapter 1, three chapters ahead. Chapter 2 is
     *  on disk, which the downloader drops for itself; only what it is handed beyond that is returned. */
    suspend fun downloadAhead(scope: TestScope): Set<ReaderCopy>
}

class MangaDownloadProbe : ReaderDownloadProbe {

    override fun toString() = "manga"

    private val ids = mapOf(
        ReaderCopy.M4 to 14L,
        ReaderCopy.M3 to 13L,
        ReaderCopy.M2 to 12L,
        ReaderCopy.M1 to 11L,
        ReaderCopy.A3 to 23L,
        ReaderCopy.A2 to 22L,
        ReaderCopy.A1 to 21L,
    )
    private val roleOf = ids.entries.associate { (role, id) -> id to role }

    override suspend fun sheetStart(scope: TestScope, row: ReaderCopy): Set<ReaderCopy> = open {
            model,
            _,
            group,
            queued,
        ->
        model.handleChapterDownload(
            group.pooledChapters.first {
                it.id == ids.getValue(row)
            },
            ChapterDownloadAction.START,
        )
        settle(queued)
    }

    override suspend fun downloadAhead(scope: TestScope): Set<ReaderCopy> = open(downloadAhead = 3) {
            model,
            state,
            _,
            queued,
        ->
        val current = state.viewerChapters!!.currChapter
        val pages = loadedPages(current, 2)
        // Download-ahead only runs while the chapter being read is itself read from disk.
        current.pageLoader = mockk<DownloadPageLoader>(relaxed = true)
        model.onPageSelected(pages[1])
        settle(queued) - ReaderCopy.A2
    }

    private suspend fun settle(queued: Set<Long>): Set<ReaderCopy> = withContext(Dispatchers.Default) {
        withTimeoutOrNull(3_000) { while (queued.isEmpty()) delay(20) }
        // Anything else a wrong rule would queue lands in the same pass.
        delay(300)
        queued.mapTo(HashSet()) { roleOf.getValue(it) }
    }

    private suspend fun <T> open(
        downloadAhead: Int = 0,
        probe: suspend (
            eu.kanade.tachiyomi.ui.reader.ReaderViewModel,
            eu.kanade.tachiyomi.ui.reader.ReaderViewModel.State,
            MergedChapterProvider.Group,
            Set<Long>,
        ) -> T,
    ): T = MangaReaderViewModelHarness.create().use { harness ->
        val m = harness.manga(M, source = M_SOURCE, title = "Gone")
        val a = harness.manga(A, source = A_SOURCE, title = "Installed")
        // Newest first, as a manga source lists them.
        val mChapters = listOf(14L, 13L, 12L, 11L).zip(listOf(4.0, 3.0, 2.0, 1.0)) { id, n ->
            harness.chapter(id, m, n)
        }
        val aChapters = listOf(23L, 22L, 21L).zip(listOf(3.0, 2.0, 1.0)) { id, n -> harness.chapter(id, a, n) }
        val stitch = listOf(
            ChapterUnit(14L, unit = 0, copyOrder = 0),
            ChapterUnit(13L, unit = 1, copyOrder = 0),
            ChapterUnit(23L, unit = 1, copyOrder = 1),
            ChapterUnit(12L, unit = 2, copyOrder = 0),
            ChapterUnit(22L, unit = 2, copyOrder = 1),
            ChapterUnit(11L, unit = 3, copyOrder = 0),
            ChapterUnit(21L, unit = 3, copyOrder = 1),
        )
        val pooled = mChapters + aChapters
        val group = MergedChapterProvider.Group(
            mangaById = mapOf(m.id to m, a.id to a),
            chapters = renderStoredStitch(pooled, stitch) { it.id }
                .mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) },
            sourceNameByMangaId = mapOf(m.id to "Gone", a.id to "Installed"),
            stitch = stitch,
            pooledChapters = pooled,
        )
        val queued = ConcurrentHashMap.newKeySet<Long>()
        harness.open(
            a,
            chapterId = 21L,
            group = group,
            preferences = mapOf("auto_download_while_reading" to downloadAhead),
            onDisk = setOf(21L, 22L),
            missingSources = setOf(M_SOURCE),
            onDownload = { chapters -> chapters.forEach { queued += it.id } },
        ) { model, state -> probe(model, state, group, queued) }
    }

    private companion object {
        const val M = 1L
        const val A = 2L
        const val M_SOURCE = 100L
        const val A_SOURCE = 200L
    }
}

class NovelDownloadProbe : ReaderDownloadProbe {

    override fun toString() = "novel"

    override suspend fun sheetStart(scope: TestScope, row: ReaderCopy): Set<ReaderCopy> = open(scope) {
            harness,
            ids,
            queued,
        ->
        val model = harness.open(ids.getValue(ReaderCopy.A1).novel, ids.getValue(ReaderCopy.A1).id)
        scope.advanceUntilIdle()
        model.downloadChapter(ids.getValue(row).id, ChapterDownloadAction.START)
        scope.advanceUntilIdle()
        queued
    }

    override suspend fun downloadAhead(scope: TestScope): Set<ReaderCopy> = open(scope) { harness, ids, queued ->
        harness.novelPreferences.autoDownloadWhileReading().set(3)
        harness.open(ids.getValue(ReaderCopy.A1).novel, ids.getValue(ReaderCopy.A1).id)
        scope.advanceUntilIdle()
        queued - ReaderCopy.A2
    }

    private class Seeded(val novel: Long, val id: Long)

    private suspend fun open(
        scope: TestScope,
        probe: suspend (NovelReaderViewModelHarness, Map<ReaderCopy, Seeded>, Set<ReaderCopy>) -> Set<ReaderCopy>,
    ): Set<ReaderCopy> = NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
        val m = harness.novel(FakeNovelSource("gone", "unregistered"))
        val a = harness.novel(harness.source("alpha"))
        val names = listOf("Opening", "Second", "Third", "Only gone has this")
        // M holds the most, so it is the stitch's first-ranked source and its copies are the ones shown.
        val ids = buildMap {
            names.forEachIndexed { i, name ->
                put(ReaderCopy.entries[i], Seeded(m, harness.chapter(m, i + 1.0, name = name).id))
            }
            names.take(3).forEachIndexed { i, name ->
                put(ReaderCopy.entries[4 + i], Seeded(a, harness.chapter(a, i + 1.0, name = name).id))
            }
        }
        harness.download(SeededChapter(ids.getValue(ReaderCopy.A1).id, ""), "<p>One on disk</p>")
        harness.download(SeededChapter(ids.getValue(ReaderCopy.A2).id, ""), "<p>Two on disk</p>")
        harness.merge(m, a)
        val roleOf = ids.entries.associate { (role, seeded) -> seeded.id to role }
        val queued = ConcurrentHashMap.newKeySet<ReaderCopy>()
        coEvery { harness.downloadManager.downloadChapters(any()) } answers {
            firstArg<List<NovelChapter>>().forEach { queued += roleOf.getValue(it.id) }
        }
        probe(harness, ids, queued)
    }
}
