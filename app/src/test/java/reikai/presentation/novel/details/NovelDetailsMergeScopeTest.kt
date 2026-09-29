package reikai.presentation.novel.details

import eu.kanade.presentation.manga.components.ChapterDownloadAction
import io.kotest.matchers.shouldBe
import io.mockk.every
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.NovelChapter
import reikai.presentation.reader.NovelReaderViewModelHarness

/**
 * The novel details list passes its merge scope through: a source chip shows and deletes only that
 * source's copy, the All list every source's. Two sources hold chapter 6; only the second's is on disk.
 */
class NovelDetailsMergeScopeTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `under a source chip a chapter whose only copy on disk is another source's is not downloaded`() =
        runTest {
            scenario(chip = true) { _, loaded -> loaded.chapters.single().id in loaded.downloadedChapterIds } shouldBe
                false
        }

    @Test
    fun `under All a chapter whose other source's copy is on disk is downloaded`() = runTest {
        scenario(chip = false) { _, loaded -> loaded.chapters.single().id in loaded.downloadedChapterIds } shouldBe
            true
    }

    @Test
    fun `a delete under a source chip leaves the other source's copy`() = runTest {
        scenario(chip = true) { harness, loaded -> harness.deletedBy(loaded.chapters.single()) } shouldBe
            setOf(LEADING_COPY)
    }

    @Test
    fun `a delete under All removes every source's copy`() = runTest {
        scenario(chip = false) { harness, loaded -> harness.deletedBy(loaded.chapters.single()) } shouldBe
            setOf(LEADING_COPY, OTHER_COPY)
    }

    private suspend fun <T> kotlinx.coroutines.test.TestScope.scenario(
        chip: Boolean,
        probe: suspend (Scenario, NovelDetailsState.Loaded) -> T,
    ): T = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
        val leading = harness.novel(harness.source("alpha"))
        val other = harness.novel(harness.source("beta"))
        val leadingCopy = harness.chapter(leading, 6.0)
        val otherCopy = harness.chapter(other, 6.0)
        harness.download(otherCopy, "<p>on disk</p>")
        harness.merge(leading, other)
        val model = harness.openDetails(leading)
        val loaded = withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                // A chip outside the group is refused, so pick it once the group has resolved.
                model.state.first { it is NovelDetailsState.Loaded && it.mergeSources.size == 2 }
                if (chip) model.selectSource(leading)
                model.state.first {
                    it is NovelDetailsState.Loaded && it.mergeSources.size == 2 && it.chapters.size == 1 &&
                        it.selectedSourceNovelId == leading.takeIf { chip }
                } as NovelDetailsState.Loaded
            }
        }
        probe(Scenario(harness, model, mapOf(leadingCopy.id to LEADING_COPY, otherCopy.id to OTHER_COPY)), loaded)
    }

    /** One opened details screen, with each seeded chapter's id named for the assertions. */
    class Scenario(
        private val harness: NovelReaderViewModelHarness,
        private val model: NovelDetailsViewModel,
        private val names: Map<Long, String>,
    ) {
        /** The copies a delete of [chapter]'s download from this screen reaches, by name. */
        suspend fun deletedBy(chapter: NovelChapter): Set<String> {
            val deleted = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()
            every { harness.downloadManager.deleteChapters(any()) } answers {
                deleted += firstArg<List<NovelChapter>>().map { it.id }
            }
            model.onChapterDownloadAction(chapter, ChapterDownloadAction.DELETE)
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(5_000) { while (deleted.isEmpty()) delay(20) }
                delay(200)
            }
            return deleted.mapNotNullTo(HashSet()) { names[it] }
        }
    }

    private companion object {
        const val LEADING_COPY = "leading"
        const val OTHER_COPY = "other"
    }
}
