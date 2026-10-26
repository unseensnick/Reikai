package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.renderStoredStitch
import reikai.domain.reader.ChapterProgress

/**
 * Incognito asked of each written chapter's own source, never of the series the reader was opened
 * from, pinned once over both readers. A series is merged from a public source holding chapter 1 and a
 * private one holding chapter 2, and chapter 2 is opened.
 */
class ReaderIncognitoConformanceTest {

    interface Probe {
        /** The chapters of the source holding chapter 2 recorded in History once the reader saves it,
         *  opened from the [fromPrivate] series or the public one, with chapter 2's source [private]. */
        suspend fun historyOfOpened(scope: TestScope, fromPrivate: Boolean, private: Boolean): List<Long>

        /** Whether a position saved in chapter 2, opened from the public series, is stored. */
        suspend fun privateChapterProgressStored(scope: TestScope): Boolean
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a private source's chapter opened from the public series stays out of history`(probe: Probe) = runTest {
        probe.historyOfOpened(this, fromPrivate = false, private = true) shouldBe emptyList()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a public source's chapter opened from the private series is recorded in history`(probe: Probe) =
        runTest {
            probe.historyOfOpened(this, fromPrivate = true, private = false).size shouldBe 1
        }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a private source's chapter opened from the public series saves no position`(probe: Probe) = runTest {
        probe.privateChapterProgressStored(this) shouldBe false
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun historyOfOpened(scope: TestScope, fromPrivate: Boolean, private: Boolean): List<Long> =
            openSecond(fromPrivate, private) { harness, model, _ ->
                model.saveHistory()
                settle()
                harness.historyOf(OPENED_OWNER)
            }

        override suspend fun privateChapterProgressStored(scope: TestScope): Boolean =
            openSecond(fromPrivate = false, private = true) { harness, model, state ->
                model.onPageSelected(loadedPages(state.viewerChapters!!.currChapter, 5)[3])
                settle()
                harness.stored(OPENED).lastPageRead != 0
            }

        /** The writes run on the app scope's IO threads, which virtual time does not reach. */
        private suspend fun settle() = withContext(Dispatchers.Default) { delay(500) }

        /** Opens chapter 2 from the [fromPrivate] series; [private] says whether chapter 2's source is. */
        private suspend fun <T> openSecond(
            fromPrivate: Boolean,
            private: Boolean,
            probe: suspend (MangaReaderViewModelHarness, ReaderViewModel, ReaderViewModel.State) -> T,
        ): T = MangaReaderViewModelHarness.create().use { harness ->
            val other = harness.manga(1L, source = 100L, title = "Other")
            val owner = harness.manga(OPENED_OWNER, source = 200L, title = "Owner")
            val first = harness.chapter(10L, other, 1.0)
            val second = harness.chapter(OPENED, owner, 2.0)
            // Newest first, as a manga source lists them.
            val stitch = listOf(ChapterUnit(second.id, 0, 0), ChapterUnit(first.id, 1, 0))
            val pooled = listOf(second, first)
            val group = MergedChapterProvider.Group(
                mangaById = mapOf(other.id to other, owner.id to owner),
                chapters = renderStoredStitch(pooled, stitch) { it.id },
                sourceNameByMangaId = mapOf(other.id to "Other", owner.id to "Owner"),
                stitch = stitch,
                pooledChapters = pooled,
            )
            val privateSource = if (private) owner.source else other.source
            harness.open(
                if (fromPrivate == private) owner else other,
                chapterId = OPENED,
                group = group,
                incognito = setOf(privateSource),
            ) { model, state -> probe(harness, model, state) }
        }

        private companion object {
            const val OPENED_OWNER = 2L
            const val OPENED = 20L
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun historyOfOpened(scope: TestScope, fromPrivate: Boolean, private: Boolean): List<Long> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val other = harness.source("alpha")
                val ownerSource = harness.source("beta")
                val otherNovel = harness.novel(other)
                val owner = harness.novel(ownerSource)
                harness.chapter(otherNovel, 1.0)
                val opened = harness.chapter(owner, 2.0)
                harness.merge(otherNovel, owner)
                harness.incognito(if (private) ownerSource else other)
                val model = harness.open(if (fromPrivate == private) owner else otherNovel, opened.id)
                scope.advanceUntilIdle()

                model.saveHistory()
                scope.advanceUntilIdle()

                harness.historyOf(owner)
            }

        override suspend fun privateChapterProgressStored(scope: TestScope): Boolean =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val public = harness.source("alpha")
                val private = harness.source("beta")
                val host = harness.novel(public)
                val member = harness.novel(private)
                harness.chapter(host, 1.0)
                val opened = harness.chapter(member, 2.0)
                harness.merge(host, member)
                harness.incognito(private)
                val model = harness.open(host, opened.id)
                scope.advanceUntilIdle()
                model.rendererLanded(model.window.value.generation)

                // The first report is where the landing put the reader; a second that moved is theirs.
                model.saveProgress(opened.id, ChapterProgress.Percent.ofWhole(40))
                model.saveProgress(opened.id, ChapterProgress.Percent.ofWhole(45))
                scope.advanceUntilIdle()

                harness.progressOf(opened) != 0L
            }
    }
}
