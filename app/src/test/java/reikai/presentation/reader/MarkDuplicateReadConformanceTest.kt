package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.renderStoredStitch
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers

/**
 * Finishing a chapter marks its other copies read as the database holds them when it finishes, not as
 * the session saw them at open. Pinned once over both readers.
 *
 * One entry lists chapters 1, 2 and 3 by group "x"; the reader opens on 1, steps to 2, the [Change]
 * happens to group "y"'s copy of chapter 2, then a Next finishes 2.
 */
class MarkDuplicateReadConformanceTest {

    enum class Change {
        /** "y"'s copy, read at open, is marked unread mid-session. */
        UNMARKED,

        /** "y"'s copy arrives mid-session, as a refresh stores it. */
        ADDED,
    }

    interface Probe {
        /** Whether "y"'s copy of chapter 2 is stored read once 2 is finished after [change]. */
        suspend fun copyReadAfterFinish(scope: TestScope, change: Change): Boolean?

        /** A series merged from a public source and one in incognito, each with chapters 1 and 2,
         *  stitched by number: whether the private copy of 1 is stored read once a Next finishes the
         *  public one. */
        suspend fun privateCopyReadAfterFinish(scope: TestScope): Boolean?
    }

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a copy marked unread mid-session is marked read when its twin is finished`(probe: Probe) = runTest {
        probe.copyReadAfterFinish(this, Change.UNMARKED) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a copy added mid-session is marked read when its twin is finished`(probe: Probe) = runTest {
        probe.copyReadAfterFinish(this, Change.ADDED) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a copy on a source in incognito stays unread when its twin is finished`(probe: Probe) = runTest {
        probe.privateCopyReadAfterFinish(this) shouldBe false
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun copyReadAfterFinish(scope: TestScope, change: Change): Boolean? =
            MangaReaderViewModelHarness.create().use { harness ->
                val manga = harness.manga(1L, source = 100L, title = "Series")
                harness.chapter(10L, manga, 1.0, scanlator = "x")
                harness.chapter(20L, manga, 2.0, scanlator = "x")
                if (change == Change.UNMARKED) harness.chapter(21L, manga, 2.0, scanlator = "y", read = true)
                harness.chapter(30L, manga, 3.0, scanlator = "x")
                val prefs = mapOf("mark_read_on_skip" to true, "skip_dupe" to true)
                harness.open(manga, chapterId = 10L, preferences = prefs) { model, _ ->
                    harness.nextAndSettle(model)
                    when (change) {
                        Change.UNMARKED -> harness.setRead(21L, read = false)
                        Change.ADDED -> harness.chapter(21L, manga, 2.0, scanlator = "y")
                    }
                    harness.nextAndSettle(model)
                    harness.stored(21L).read
                }
            }

        override suspend fun privateCopyReadAfterFinish(scope: TestScope): Boolean? =
            MangaReaderViewModelHarness.create().use { harness ->
                val public = harness.manga(1L, source = 100L, title = "Public")
                val private = harness.manga(2L, source = 200L, title = "Private")
                // Newest first, as a manga source lists them; unit by unit, the public copy leading.
                val publicChapters = listOf(harness.chapter(20L, public, 2.0), harness.chapter(10L, public, 1.0))
                val privateChapters = listOf(harness.chapter(21L, private, 2.0), harness.chapter(11L, private, 1.0))
                val stitch = publicChapters.indices.flatMap { unit ->
                    listOf(
                        ChapterUnit(chapterId = publicChapters[unit].id, unit = unit, copyOrder = 0),
                        ChapterUnit(chapterId = privateChapters[unit].id, unit = unit, copyOrder = 1),
                    )
                }
                val pooled = publicChapters + privateChapters
                val group = MergedChapterProvider.Group(
                    mangaById = mapOf(public.id to public, private.id to private),
                    chapters = renderStoredStitch(pooled, stitch) { it.id }
                        .mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) },
                    sourceNameByMangaId = mapOf(public.id to "Public", private.id to "Private"),
                    stitch = stitch,
                    pooledChapters = pooled,
                )
                harness.open(
                    public,
                    chapterId = 10L,
                    group = group,
                    preferences = mapOf("mark_read_on_skip" to true),
                    incognito = setOf(private.source),
                ) { model, _ ->
                    harness.nextAndSettle(model)
                    harness.stored(11L).read
                }
            }

        private suspend fun MangaReaderViewModelHarness.nextAndSettle(model: ReaderViewModel) =
            settle(model) { model.loadNextChapter() }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun copyReadAfterFinish(scope: TestScope, change: Change): Boolean? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                harness.novelPreferences.readerMarkReadOnSkip().set(true)
                harness.novelPreferences.readerSkipDuplicateChapters().set(true)
                val novel = harness.novel(harness.source("src"))
                val one = harness.chapter(novel, 1.0, scanlator = "x")
                harness.chapter(novel, 2.0, scanlator = "x")
                suspend fun copy(read: Boolean) =
                    harness.chapter(novel, 2.0, read, url = "/2-y", sourceOrder = 3L, scanlator = "y")
                val seeded = if (change == Change.UNMARKED) copy(true) else null
                harness.chapter(novel, 3.0, sourceOrder = 4L, scanlator = "x")
                val model = harness.open(novel, one.id)
                scope.advanceUntilIdle()

                model.nextChapter()
                scope.advanceUntilIdle()
                val other = when (change) {
                    Change.UNMARKED -> seeded!!.also { harness.markRead(it, read = false) }
                    Change.ADDED -> copy(false)
                }
                model.nextChapter()
                scope.advanceUntilIdle()

                harness.isRead(other)
            }

        override suspend fun privateCopyReadAfterFinish(scope: TestScope): Boolean? =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                harness.novelPreferences.readerMarkReadOnSkip().set(true)
                val publicSource = harness.source("alpha")
                val privateSource = harness.source("beta")
                val public = harness.novel(publicSource)
                val private = harness.novel(privateSource)
                val opened = harness.chapter(public, 1.0)
                harness.chapter(public, 2.0)
                val privateCopy = harness.chapter(private, 1.0)
                harness.chapter(private, 2.0)
                harness.merge(public, private)
                harness.incognito(privateSource)
                val model = harness.open(public, opened.id)
                scope.advanceUntilIdle()

                model.nextChapter()
                scope.advanceUntilIdle()

                harness.isRead(privateCopy)
            }
    }
}
