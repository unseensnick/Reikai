package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.ReaderViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
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
    }

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

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

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaProbe(), NovelProbe())

        val OWNER = ReaderOrientation.PORTRAIT.flagValue.toLong()
        val SIBLING = ReaderOrientation.LANDSCAPE.flagValue.toLong()
        val CHANGED = ReaderOrientation.REVERSE_PORTRAIT.flagValue.toLong()
        private val MASK = ReaderOrientation.MASK.toLong()

        /** The readers write on the IO dispatcher, which virtual time does not reach, so this polls; a busy CI
         *  runner has taken over 3 s, and a settled write ends the wait at once. */
        suspend fun settlesAt(expected: Long, read: suspend () -> Long): Long = withContext(Dispatchers.Default) {
            withTimeoutOrNull(15_000) { while (read() != expected) delay(20) }
            read()
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

        private lateinit var harness: MangaReaderViewModelHarness

        private suspend fun storedRotation(id: Long) = harness.storedManga(id).viewerFlags and MASK

        /** The owner and the sibling, one chapter each, grouped with the owner first; opens [openedId]'s. */
        private suspend fun <T> open(openedId: Long, probe: suspend (ReaderViewModel) -> T): T =
            MangaReaderViewModelHarness.create().use { harness ->
                this.harness = harness
                harness.manga(OWNER_ID, source = 100L, title = "Owner")
                harness.manga(SIBLING_ID, source = 200L, title = "Sibling")
                harness.setViewerFlags(OWNER_ID, OWNER)
                harness.setViewerFlags(SIBLING_ID, SIBLING)
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
                val opened = if (openedId == OWNER_ID) owner else sibling
                harness.open(opened, chapterId = if (openedId == OWNER_ID) 11L else 21L, group = group) { model, _ ->
                    probe(model)
                }
            }

        private companion object {
            const val OWNER_ID = 1L
            const val SIBLING_ID = 2L
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

        /** The owner and the sibling, grouped with the owner first. */
        private suspend fun group(harness: NovelReaderViewModelHarness): Pair<Long, Long> {
            val owner = harness.novel(harness.source("alpha"))
            val sibling = harness.novel(harness.source("beta"))
            harness.updateNovel(owner) { viewerFlags = OWNER }
            harness.updateNovel(sibling) { viewerFlags = SIBLING }
            harness.merge(owner, sibling)
            return owner to sibling
        }
    }
}
