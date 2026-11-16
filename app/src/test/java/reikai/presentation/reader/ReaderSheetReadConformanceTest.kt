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
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.renderStoredStitch

/**
 * A mark from the reader's chapter sheet lands in the database whatever the session saw of the chapter
 * when it opened. The manga reader built its targets from the chapters it loaded at open, so once a
 * chapter was marked read there, marking it unread wrote nothing. Pinned once over both readers.
 */
class ReaderSheetReadConformanceTest {

    /** One reader opened on chapter 1, with chapter 2 unread. */
    interface Probe {
        /** Marks chapter 2 read from the sheet, then unread when [unmark]: whether it is stored read afterwards. */
        suspend fun readAfterMarking(scope: TestScope, unmark: Boolean): Boolean?

        /** Marks chapters 2 and 3 read in one write, as the sheet's bulk bar does: whether each is stored read. */
        suspend fun readAfterMarkingTwo(scope: TestScope): List<Boolean?>

        /** With Remove after marked as read on, marks the open chapter and another read: whether each lost its download. */
        suspend fun downloadsRemovedMarkingOpenAndOther(scope: TestScope): List<Boolean>

        /** A series merged from two sources, each with chapters 5 and 6, opened on the first's chapter 5. Marks
         *  the first's chapter 6 read: whether the second source's copy is stored read. */
        suspend fun mergedCopyReadAfterMarking(scope: TestScope): Boolean?

        /** The same, with Remove after marked as read on, marking the first's chapters 5 and 6 read: whether the
         *  second source's copy of each lost its download. */
        suspend fun mergedCopyDownloadsRemovedMarkingOpenAndOther(scope: TestScope): List<Boolean>
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `marking a chapter read from the sheet stores it read`(probe: Probe) = runTest {
        probe.readAfterMarking(this, unmark = false) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `marking a chapter read and then unread from the sheet leaves it unread`(probe: Probe) = runTest {
        probe.readAfterMarking(this, unmark = true) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `marking the open chapter read from the sheet keeps its download`(probe: Probe) = runTest {
        probe.downloadsRemovedMarkingOpenAndOther(this) shouldBe listOf(false, true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `marking two chapters read at once from the sheet stores both read`(probe: Probe) = runTest {
        probe.readAfterMarkingTwo(this) shouldBe listOf(true, true)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `marking a merged chapter read from the sheet marks every source's copy`(probe: Probe) = runTest {
        probe.mergedCopyReadAfterMarking(this) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `marking the open merged chapter read keeps every source's copy of it on disk`(probe: Probe) = runTest {
        probe.mergedCopyDownloadsRemovedMarkingOpenAndOther(this) shouldBe listOf(false, true)
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())

        /** Waits for [id]'s download to be removed, the sign the whole mark has run. */
        private suspend fun removed(deleted: List<Long>, id: Long) = withContext(Dispatchers.Default) {
            withTimeoutOrNull(3_000) { while (id !in deleted) delay(20) }
        }

        /** The sheet writes on the IO dispatcher, which virtual time does not reach, so this polls. */
        private suspend fun settlesAt(expected: Boolean, read: suspend () -> Boolean?): Boolean? =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(3_000) { while (read() != expected) delay(20) }
                read()
            }
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun readAfterMarking(scope: TestScope, unmark: Boolean): Boolean? =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0)
                val other = harness.chapter(20L, manga, 2.0)
                harness.open(manga, chapterId = 10L) { model, _ ->
                    model.setChapterReadStatus(listOf(other), read = true)
                    val marked = settlesAt(true) { harness.stored(other.id).read }
                    // An unmark of a mark that never landed would pass on the seeded state.
                    if (!unmark || marked != true) return@open marked
                    model.setChapterReadStatus(listOf(other), read = false)
                    settlesAt(false) { harness.stored(other.id).read }
                }
            }

        override suspend fun readAfterMarkingTwo(scope: TestScope): List<Boolean?> =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0)
                val marked = listOf(harness.chapter(20L, manga, 2.0), harness.chapter(30L, manga, 3.0))
                harness.open(manga, chapterId = 10L) { model, _ ->
                    model.setChapterReadStatus(marked, read = true)
                    marked.map { chapter -> settlesAt(true) { harness.stored(chapter.id).read } }
                }
            }

        override suspend fun downloadsRemovedMarkingOpenAndOther(scope: TestScope): List<Boolean> =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                val opened = harness.chapter(10L, manga, 1.0)
                val other = harness.chapter(20L, manga, 2.0)
                harness.open(
                    manga,
                    chapterId = opened.id,
                    preferences = mapOf("pref_remove_after_marked_as_read_key" to true),
                ) { model, _ ->
                    model.setChapterReadStatus(listOf(opened, other), read = true)
                    removed(harness.deletedAfterRead, other.id)
                    listOf(opened.id in harness.deletedAfterRead, other.id in harness.deletedAfterRead)
                }
            }

        override suspend fun mergedCopyReadAfterMarking(scope: TestScope): Boolean? = openMerged { harness, model ->
            model.setChapterReadStatus(listOf(harness.stored(FIRST_SIX)), read = true)
            settlesAt(true) { harness.stored(SECOND_SIX).read }
        }

        override suspend fun mergedCopyDownloadsRemovedMarkingOpenAndOther(scope: TestScope): List<Boolean> =
            openMerged(mapOf("pref_remove_after_marked_as_read_key" to true)) { harness, model ->
                model.setChapterReadStatus(listOf(harness.stored(FIRST_FIVE), harness.stored(FIRST_SIX)), read = true)
                removed(harness.deletedAfterRead, SECOND_SIX)
                listOf(SECOND_FIVE in harness.deletedAfterRead, SECOND_SIX in harness.deletedAfterRead)
            }

        /** Two sources carrying chapters 5 and 6, newest first, stitched unit by unit, opened on the first's 5. */
        private suspend fun <T> openMerged(
            preferences: Map<String, Any> = emptyMap(),
            probe: suspend (MangaReaderViewModelHarness, ReaderViewModel) -> T,
        ): T = MangaReaderViewModelHarness.create().use { harness ->
            val first = harness.manga(1L, source = 100L, title = "First")
            val second = harness.manga(2L, source = 200L, title = "Second")
            val firsts = listOf(harness.chapter(FIRST_SIX, first, 6.0), harness.chapter(FIRST_FIVE, first, 5.0))
            val seconds = listOf(harness.chapter(SECOND_SIX, second, 6.0), harness.chapter(SECOND_FIVE, second, 5.0))
            val stitch = firsts.indices.flatMap { unit ->
                listOf(ChapterUnit(firsts[unit].id, unit, 0), ChapterUnit(seconds[unit].id, unit, 1))
            }
            val pooled = firsts + seconds
            val group = MergedChapterProvider.Group(
                mangaById = mapOf(first.id to first, second.id to second),
                chapters = renderStoredStitch(pooled, stitch) { it.id }
                    .mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) },
                sourceNameByMangaId = mapOf(first.id to "First", second.id to "Second"),
                stitch = stitch,
                pooledChapters = pooled,
            )
            harness.open(first, chapterId = FIRST_FIVE, group = group, preferences = preferences) { model, _ ->
                probe(harness, model)
            }
        }

        private companion object {
            const val FIRST_FIVE = 15L
            const val FIRST_SIX = 16L
            const val SECOND_FIVE = 25L
            const val SECOND_SIX = 26L
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun readAfterMarking(scope: TestScope, unmark: Boolean): Boolean? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novel = harness.novel(harness.source("alpha"))
                val opened = harness.chapter(novel, 1.0)
                val other = harness.chapter(novel, 2.0)
                val model = harness.open(novel, opened.id)
                scope.advanceUntilIdle()

                model.setChapterRead(listOf(other.id), read = true)
                val marked = settlesAt(true) { harness.isRead(other) }
                if (!unmark || marked != true) return@use marked
                model.setChapterRead(listOf(other.id), read = false)
                settlesAt(false) { harness.isRead(other) }
            }

        override suspend fun readAfterMarkingTwo(scope: TestScope): List<Boolean?> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val novel = harness.novel(harness.source("alpha"))
                val opened = harness.chapter(novel, 1.0)
                val marked = listOf(harness.chapter(novel, 2.0), harness.chapter(novel, 3.0))
                val model = harness.open(novel, opened.id)
                scope.advanceUntilIdle()

                model.setChapterRead(marked.map { it.id }, read = true)
                marked.map { chapter -> settlesAt(true) { harness.isRead(chapter) } }
            }

        override suspend fun downloadsRemovedMarkingOpenAndOther(scope: TestScope): List<Boolean> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                harness.novelPreferences.removeAfterMarkedAsRead().set(true)
                val novel = harness.novel(harness.source("alpha"))
                val opened = harness.chapter(novel, 1.0)
                val other = harness.chapter(novel, 2.0)
                val model = harness.open(novel, opened.id)
                scope.advanceUntilIdle()

                model.setChapterRead(listOf(opened.id, other.id), read = true)
                removed(harness.deletedAfterRead, other.id)
                listOf(opened.id in harness.deletedAfterRead, other.id in harness.deletedAfterRead)
            }

        override suspend fun mergedCopyReadAfterMarking(scope: TestScope): Boolean? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val first = harness.novel(harness.source("alpha"))
                val second = harness.novel(harness.source("beta"))
                val opened = harness.chapter(first, 5.0)
                val marked = harness.chapter(first, 6.0)
                harness.chapter(second, 5.0)
                val copy = harness.chapter(second, 6.0)
                harness.merge(first, second)
                val model = harness.open(first, opened.id)
                scope.advanceUntilIdle()

                model.setChapterRead(listOf(marked.id), read = true)
                settlesAt(true) { harness.isRead(copy) }
            }

        override suspend fun mergedCopyDownloadsRemovedMarkingOpenAndOther(scope: TestScope): List<Boolean> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                harness.novelPreferences.removeAfterMarkedAsRead().set(true)
                val first = harness.novel(harness.source("alpha"))
                val second = harness.novel(harness.source("beta"))
                val opened = harness.chapter(first, 5.0)
                val other = harness.chapter(first, 6.0)
                val openedCopy = harness.chapter(second, 5.0)
                val otherCopy = harness.chapter(second, 6.0)
                harness.merge(first, second)
                val model = harness.open(first, opened.id)
                scope.advanceUntilIdle()

                model.setChapterRead(listOf(opened.id, other.id), read = true)
                removed(harness.deletedAfterRead, otherCopy.id)
                listOf(openedCopy.id in harness.deletedAfterRead, otherCopy.id in harness.deletedAfterRead)
            }
    }
}
