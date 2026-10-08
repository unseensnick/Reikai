package reikai.presentation.reader

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Skip duplicates keeps one copy of each chapter number, and the skip filters then say which chapters a
 * forward step may land on. Chapter 5 is listed twice, the first copy read: with Skip read on too, the
 * step from 4 has to reach the unread copy of 5, never jump to 6. Pinned once over both readers.
 */
class SkipDuplicateForwardConformanceTest {

    /** One reader opened on chapter 4 of an entry listing 4, 5 (read), 5 (unread) and 6. */
    interface Probe {
        /** Whether the next chapter from 4 is the unread copy of 5. */
        suspend fun nextIsTheUnreadCopy(scope: TestScope): Boolean

        /** Of chapter 5's copies from groups y (listed first) and x, whether the step from 4 (x) reaches x's. */
        suspend fun nextIsTheSameGroupsCopy(scope: TestScope): Boolean

        /** Under Downloaded only, of chapter 5's copies only the second on disk, whether the step from 4 reaches it. */
        suspend fun nextIsTheCopyOnDisk(scope: TestScope, skipFiltered: Boolean): Boolean
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a forward step reaches the unread copy of a duplicated chapter`(probe: Probe) = runTest {
        probe.nextIsTheUnreadCopy(this) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a forward step keeps to the current chapter's group`(probe: Probe) = runTest {
        probe.nextIsTheSameGroupsCopy(this) shouldBe true
    }

    /** Keeping the copy off disk let Downloaded only drop it, and chapter 5 with it. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `with Downloaded only a forward step reaches the copy on disk`(probe: Probe) = runTest {
        probe.nextIsTheCopyOnDisk(this, skipFiltered = false) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `with Downloaded only and Skip filtered a forward step reaches the copy on disk`(probe: Probe) = runTest {
        probe.nextIsTheCopyOnDisk(this, skipFiltered = true) shouldBe true
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    /** The read copy is the current chapter's own scanlator's, so origin alone would keep it. */
    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun nextIsTheUnreadCopy(scope: TestScope): Boolean =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(40L, manga, 4.0, scanlator = "x")
                harness.chapter(50L, manga, 5.0, scanlator = "x", read = true, order = 951L)
                val unread = harness.chapter(51L, manga, 5.0, scanlator = "y", order = 950L)
                harness.chapter(60L, manga, 6.0, scanlator = "x")
                val skips = mapOf("skip_read" to true, "skip_dupe" to true)
                harness.open(manga, chapterId = 40L, preferences = skips) { _, state ->
                    state.viewerChapters?.nextChapter?.chapter?.id == unread.id
                }
            }

        override suspend fun nextIsTheSameGroupsCopy(scope: TestScope): Boolean =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(40L, manga, 4.0, scanlator = "x")
                harness.chapter(50L, manga, 5.0, scanlator = "y", order = 951L)
                val same = harness.chapter(51L, manga, 5.0, scanlator = "x", order = 950L)
                harness.chapter(60L, manga, 6.0, scanlator = "x")
                harness.open(manga, chapterId = 40L, preferences = mapOf("skip_dupe" to true)) { _, state ->
                    state.viewerChapters?.nextChapter?.chapter?.id == same.id
                }
            }

        /** The copy off disk is the current chapter's own scanlator's, so origin alone would keep it. */
        override suspend fun nextIsTheCopyOnDisk(scope: TestScope, skipFiltered: Boolean): Boolean =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(40L, manga, 4.0, scanlator = "x")
                harness.chapter(50L, manga, 5.0, scanlator = "x", order = 951L)
                val onDisk = harness.chapter(51L, manga, 5.0, scanlator = "y", order = 950L)
                harness.chapter(60L, manga, 6.0, scanlator = "x")
                harness.open(
                    manga,
                    chapterId = 40L,
                    preferences = mapOf("skip_dupe" to true, "skip_filtered" to skipFiltered),
                    onDisk = setOf(40L, 51L, 60L),
                    downloadedOnly = true,
                ) { _, state ->
                    state.viewerChapters?.nextChapter?.chapter?.id == onDisk.id
                }
            }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun nextIsTheUnreadCopy(scope: TestScope): Boolean =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novel = harness.novel(harness.source("alpha"))
                val four = harness.chapter(novel, 4.0)
                harness.chapter(novel, 5.0, read = true)
                val unread = harness.chapter(novel, 5.0, url = "/chapter/$novel/5-again", sourceOrder = 6L)
                harness.chapter(novel, 6.0, sourceOrder = 7L)
                harness.novelPreferences.readerSkipRead().set(true)
                harness.novelPreferences.readerSkipDuplicateChapters().set(true)
                val model = harness.open(novel, four.id)
                scope.advanceUntilIdle()

                model.chapterNeighbours.value.next == unread.id
            }

        override suspend fun nextIsTheSameGroupsCopy(scope: TestScope): Boolean =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novel = harness.novel(harness.source("alpha"))
                val four = harness.chapter(novel, 4.0, scanlator = "x")
                harness.chapter(novel, 5.0, scanlator = "y")
                val same = harness.chapter(novel, 5.0, url = "/chapter/$novel/5-x", sourceOrder = 6L, scanlator = "x")
                harness.chapter(novel, 6.0, sourceOrder = 7L, scanlator = "x")
                harness.novelPreferences.readerSkipDuplicateChapters().set(true)
                val model = harness.open(novel, four.id)
                scope.advanceUntilIdle()

                model.chapterNeighbours.value.next == same.id
            }

        override suspend fun nextIsTheCopyOnDisk(scope: TestScope, skipFiltered: Boolean): Boolean =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novel = harness.novel(harness.source("alpha"))
                val four = harness.chapter(novel, 4.0)
                harness.chapter(novel, 5.0)
                val onDisk = harness.chapter(novel, 5.0, url = "/chapter/$novel/5-again", sourceOrder = 6L)
                val six = harness.chapter(novel, 6.0, sourceOrder = 7L)
                listOf(four, onDisk, six).forEach { harness.download(it, "text") }
                harness.downloadedOnly.set(true)
                harness.novelPreferences.readerSkipFiltered().set(skipFiltered)
                harness.novelPreferences.readerSkipDuplicateChapters().set(true)
                val model = harness.open(novel, four.id)
                scope.advanceUntilIdle()

                model.chapterNeighbours.value.next == onDisk.id
            }
    }
}
