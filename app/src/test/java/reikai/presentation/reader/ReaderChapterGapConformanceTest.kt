package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.viewer.calculateChapterGap
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
import reikai.domain.chapter.hiddenChapterKey
import reikai.presentation.reader.text.NovelSeam

/**
 * A boundary the reader steps across because chapter 2 is passed over is not a gap: chapter 2 still
 * exists, so the marker between 1 and 3 counts nothing. Pinned once over both readers.
 */
class ReaderChapterGapConformanceTest {

    /** One reader opened on chapter 1 of an entry listing 1, 2 and 3, where 2 is passed over. */
    interface Probe {
        /** The missing count the marker between 1 and the next chapter shows, with 2 read and Skip read
         *  on, or with 2 hidden. */
        suspend fun missingAtTheStep(scope: TestScope, hidden: Boolean): Int
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a chapter the skip filters step over is not counted missing`(probe: Probe) = runTest {
        probe.missingAtTheStep(this, hidden = false) shouldBe 0
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a hidden chapter is not counted missing`(probe: Probe) = runTest {
        probe.missingAtTheStep(this, hidden = true) shouldBe 0
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun missingAtTheStep(scope: TestScope, hidden: Boolean): Int =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0)
                val second = harness.chapter(20L, manga, 2.0, read = !hidden)
                harness.chapter(30L, manga, 3.0)
                val preferences = if (hidden) {
                    mapOf("manga_hidden_chapters" to setOf(hiddenChapterKey("100", second.url)))
                } else {
                    mapOf("skip_read" to true)
                }
                harness.open(manga, chapterId = 10L, preferences = preferences) { _, state ->
                    val chapters = state.viewerChapters!!
                    check(chapters.nextChapter?.chapter?.id == 30L)
                    calculateChapterGap(chapters.nextChapter, chapters.currChapter)
                }
            }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun missingAtTheStep(scope: TestScope, hidden: Boolean): Int =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novel = harness.novel(harness.source(SOURCE))
                val first = harness.chapter(novel, 1.0, progressPercent = 50)
                val second = harness.chapter(novel, 2.0, read = !hidden)
                val third = harness.chapter(novel, 3.0)
                if (hidden) {
                    harness.novelPreferences.hiddenChapters().set(setOf(hiddenChapterKey(SOURCE, second.url)))
                } else {
                    harness.novelPreferences.readerSkipRead().set(true)
                }
                val model = harness.open(novel, first.id)
                scope.advanceUntilIdle()
                harness.novelPreferences.readerAutoLoadNextAt().set(30)
                scope.advanceUntilIdle()

                val (finished, next) = model.window.value.chapters
                check(next.chapterId == third.id)
                NovelSeam.between(finished, next).missingChapters
            }

        private companion object {
            const val SOURCE = "alpha"
        }
    }
}
