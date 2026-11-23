package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.renderStoredStitch
import reikai.domain.novel.NovelPageLayout
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.domain.manga.model.Manga

/**
 * A merged series keeps one set of reader flags, its settings owner's, whichever source the reader opens
 * through, and a change made in the reader reaches every source. Runs each real reader over a group of
 * an owner rotated one way and a sibling rotated another; rotation is a flag both types keep.
 */
class ReaderViewerFlagsConformanceTest {

    interface Probe {
        /** Opens a chapter of the sibling: the rotation the reader takes. */
        suspend fun rotationShownThroughSibling(scope: TestScope): Long

        /** Opens a chapter of the owner and turns the rotation: what each source stores. */
        suspend fun rotationStoredAfterChange(scope: TestScope): List<Long?>

        /** The owner reads paged one way and the sibling another. Opens a chapter of the sibling and turns
         *  the rotation: whether the reader still reads the way the owner does. */
        suspend fun ownerReadingModeKeptAfterRotationThroughSibling(scope: TestScope): Boolean

        /** Opens a chapter of the owner, turns the rotation while the sibling's write is slow, and leaves the
         *  reader at once: what each source stores. */
        suspend fun rotationStoredAfterClose(scope: TestScope): List<Long?>
    }

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `opening through a sibling takes the owner's rotation`(probe: Probe) = runTest {
        probe.rotationShownThroughSibling(this) shouldBe OWNER
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a rotation changed in the reader is stored on every source`(probe: Probe) = runTest {
        probe.rotationStoredAfterChange(this) shouldBe listOf(CHANGED, CHANGED)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a rotation changed through a sibling keeps the owner's reading mode`(probe: Probe) = runTest {
        probe.ownerReadingModeKeptAfterRotationThroughSibling(this) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a rotation picked as the reader closes is stored on every source`(probe: Probe) = runTest {
        probe.rotationStoredAfterClose(this) shouldBe listOf(CHANGED, CHANGED)
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())

        val OWNER = ReaderOrientation.PORTRAIT.flagValue.toLong()
        val SIBLING = ReaderOrientation.LANDSCAPE.flagValue.toLong()
        val CHANGED = ReaderOrientation.REVERSE_PORTRAIT.flagValue.toLong()
        private val MASK = ReaderOrientation.MASK.toLong()

        /** [read] once it answers [expected], or once the readers' writes have had all the virtual time they need. */
        suspend fun settlesAt(expected: Long, read: suspend () -> Long): Long {
            withTimeoutOrNull(15_000) { while (read() != expected) delay(20) }
            return read()
        }
    }

    class MangaProbe : Probe {
        override fun toString() = "manga"

        override suspend fun rotationShownThroughSibling(scope: TestScope): Long =
            open(openedId = SIBLING_ID) { model -> (model.state.value.manga?.viewerFlags ?: 0L) and MASK }

        override suspend fun rotationStoredAfterChange(scope: TestScope): List<Long?> = open(
            openedId = OWNER_ID,
        ) { model ->
            model.setMangaOrientationType(ReaderOrientation.REVERSE_PORTRAIT)
            listOf(OWNER_ID, SIBLING_ID).map { id -> settlesAt(CHANGED) { storedRotation(id) } }
        }

        // The state re-read after the write is what the next viewer rebuild reads its mode from.
        override suspend fun ownerReadingModeKeptAfterRotationThroughSibling(scope: TestScope): Boolean = open(
            openedId = SIBLING_ID,
            ownerMode = ReadingMode.WEBTOON.flagValue.toLong(),
            siblingMode = ReadingMode.LEFT_TO_RIGHT.flagValue.toLong(),
        ) { model ->
            model.setMangaOrientationType(ReaderOrientation.REVERSE_PORTRAIT)
            settlesAt(CHANGED) { (model.state.value.manga?.viewerFlags ?: 0L) and MASK }
            ((model.state.value.manga?.viewerFlags ?: 0L) and READING_MODE_MASK) ==
                ReadingMode.WEBTOON.flagValue.toLong()
        }

        override suspend fun rotationStoredAfterClose(scope: TestScope): List<Long?> = withGroup { owner, group ->
            harness.open(owner, chapterId = 11L, group = group, slowFlagWrite = { id, _ ->
                id == SIBLING_ID
            }) { model, _ ->
                model.setMangaOrientationType(ReaderOrientation.REVERSE_PORTRAIT)
            }
            listOf(OWNER_ID, SIBLING_ID).map { id -> settlesAt(CHANGED) { storedRotation(id) } }
        }

        private lateinit var harness: MangaReaderViewModelHarness

        private suspend fun storedRotation(id: Long) = harness.storedManga(id).viewerFlags and MASK

        /** The owner and the sibling, one chapter each, grouped with the owner first; opens [openedId]'s. */
        private suspend fun <T> open(
            openedId: Long,
            ownerMode: Long = 0L,
            siblingMode: Long = 0L,
            probe: suspend (ReaderViewModel) -> T,
        ): T = withGroup(ownerMode, siblingMode) { owner, group ->
            val opened = if (openedId == OWNER_ID) owner else harness.storedManga(SIBLING_ID)
            harness.open(opened, chapterId = if (openedId == OWNER_ID) 11L else 21L, group = group) { model, _ ->
                probe(model)
            }
        }

        /** The owner and the sibling, one chapter each, grouped with the owner first; [block] gets the owner. */
        private suspend fun <T> withGroup(
            ownerMode: Long = 0L,
            siblingMode: Long = 0L,
            block: suspend (Manga, MergedChapterProvider.Group) -> T,
        ): T = MangaReaderViewModelHarness.create().use { harness ->
            this.harness = harness
            harness.manga(OWNER_ID, source = 100L, title = "Owner")
            harness.manga(SIBLING_ID, source = 200L, title = "Sibling")
            harness.setViewerFlags(OWNER_ID, OWNER or ownerMode)
            harness.setViewerFlags(SIBLING_ID, SIBLING or siblingMode)
            val owner = harness.storedManga(OWNER_ID)
            val sibling = harness.storedManga(SIBLING_ID)
            val pooled = listOf(harness.chapter(11L, owner, 1.0), harness.chapter(21L, sibling, 2.0))
            val stitch = pooled.mapIndexed { unit, chapter ->
                ChapterUnit(chapterId = chapter.id, unit = unit, copyOrder = 0)
            }
            val group = MergedChapterProvider.Group(
                mangaById = mapOf(owner.id to owner, sibling.id to sibling),
                chapters = renderStoredStitch(pooled, stitch) { it.id }
                    .mapIndexed { index, chapter -> chapter.copy(sourceOrder = index.toLong()) },
                sourceNameByMangaId = mapOf(owner.id to "Owner", sibling.id to "Sibling"),
                stitch = stitch,
                pooledChapters = pooled,
            )
            block(owner, group)
        }

        private companion object {
            const val OWNER_ID = 1L
            const val SIBLING_ID = 2L
            const val READING_MODE_MASK = ReadingMode.MASK.toLong()
        }
    }

    class NovelProbe : Probe {
        override fun toString() = "novel"

        override suspend fun rotationShownThroughSibling(scope: TestScope): Long =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val (_, sibling) = group(harness)
                val model = harness.open(sibling, harness.chapter(sibling, 2.0).id)
                scope.advanceUntilIdle()

                model.settings.value.orientation.toLong()
            }

        override suspend fun rotationStoredAfterChange(scope: TestScope): List<Long?> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val (owner, sibling) = group(harness)
                val model = harness.open(owner, harness.chapter(owner, 1.0).id)
                scope.advanceUntilIdle()

                model.setOrientation(CHANGED.toInt())
                listOf(owner, sibling).map { id -> settlesAt(CHANGED) { harness.viewerFlagsOf(id) and MASK } }
            }

        override suspend fun ownerReadingModeKeptAfterRotationThroughSibling(scope: TestScope): Boolean =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val (_, sibling) = group(harness, ownerLayout = NovelPageLayout.flagOf(NovelPageLayout.PAGED_LTR))
                val model = harness.open(sibling, harness.chapter(sibling, 2.0).id)
                scope.advanceUntilIdle()

                model.setOrientation(CHANGED.toInt())
                scope.advanceUntilIdle()

                model.effectivePageLayout.value == NovelPageLayout.PAGED_LTR
            }

        override suspend fun rotationStoredAfterClose(scope: TestScope): List<Long?> =
            NovelReaderViewModelHarness.create(scope.testScheduler).use { harness ->
                val (owner, sibling) = group(harness)
                harness.slowFlagWrite = { id, _ -> id == sibling }
                val model = harness.open(owner, harness.chapter(owner, 1.0).id)
                scope.advanceUntilIdle()

                model.setOrientation(CHANGED.toInt())
                harness.leave()
                listOf(owner, sibling).map { id -> settlesAt(CHANGED) { harness.viewerFlagsOf(id) and MASK } }
            }

        /** The owner and the sibling, grouped with the owner first; [ownerLayout] is the owner's reading mode. */
        private suspend fun group(harness: NovelReaderViewModelHarness, ownerLayout: Long = 0L): Pair<Long, Long> {
            val owner = harness.novel(harness.source("alpha"))
            val sibling = harness.novel(harness.source("beta"))
            harness.updateNovel(owner) { viewerFlags = OWNER or ownerLayout }
            harness.updateNovel(sibling) { viewerFlags = SIBLING }
            harness.merge(owner, sibling)
            return owner to sibling
        }
    }
}
