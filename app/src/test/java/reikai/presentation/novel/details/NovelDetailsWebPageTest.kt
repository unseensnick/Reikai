package reikai.presentation.novel.details

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import reikai.presentation.details.EntryWebPage
import reikai.presentation.reader.NovelReaderViewModelHarness

/** The page a novel's WebView, Share, Copy link and assistant link open is the viewed member's, as on manga. */
class NovelDetailsWebPageTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a merged novel's page follows the selected chip to that source's own address`() = runTest {
        val (page, sibling) = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            val anchor = harness.novel(harness.source("alpha"))
            val sibling = harness.novel(harness.source("beta"))
            harness.merge(anchor, sibling)
            val model = harness.openDetails(anchor)
            val loaded = withContext(Dispatchers.Default) {
                withTimeout(10_000) {
                    // A chip outside the group is refused, so pick it once the group has resolved.
                    model.state.first { it is NovelDetailsState.Loaded && it.mergeSources.size == 2 }
                    model.selectSource(sibling)
                    model.state.first {
                        it is NovelDetailsState.Loaded && it.displayNovel.id == sibling && it.webPage != null
                    } as NovelDetailsState.Loaded
                }
            }
            loaded.webPage to sibling
        }

        page shouldBe
            EntryWebPage("https://beta.example/novel/beta/Novel", SourceKey.Novel("beta"), EntryId.Novel(sibling))
    }
}
