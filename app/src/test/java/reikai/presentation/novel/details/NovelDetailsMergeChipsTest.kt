package reikai.presentation.novel.details

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.novel.LnSourceIdentity
import reikai.presentation.reader.FakeNovelSource
import reikai.presentation.reader.NovelReaderViewModelHarness
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers

class NovelDetailsMergeChipsTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers(UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    /** A manga member's chip shows its stub's stored name, so a novel member's keeps its last seen one. */
    @Test
    fun `a merged member whose source is uninstalled is named as it was last seen`() = runTest {
        val names = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.novelPreferences.seenNovelSources().set(mapOf("gone" to LnSourceIdentity(name = "Old Site")))
            val leading = harness.novel(harness.source("alpha"))
            val other = harness.novel(FakeNovelSource("gone", "unregistered"))
            harness.merge(leading, other)
            val model = harness.openDetails(leading)
            withTimeout(30_000) {
                model.state.first { it is NovelDetailsState.Loaded && it.mergeSources.size == 2 }
                    .let { (it as NovelDetailsState.Loaded).mergeSources.map { chip -> chip.sourceName } }
            }
        }

        names.toSet() shouldBe setOf("alpha", "Old Site")
    }

    /** The stitch ranks a member by its stored source, so the dialog must too, installed or not. */
    @Test
    fun `Manage sources ranks an uninstalled preferred member first`() = runTest {
        val top = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.reikaiLibraryPreferences.preferredNovelSources.set(listOf("gone"))
            val leading = harness.novel(harness.source("alpha"))
            val preferred = harness.novel(FakeNovelSource("gone", "unregistered"))
            // The installed member holds more chapters, so chapter count alone would put it first.
            harness.chapter(leading, 1.0)
            harness.chapter(leading, 2.0)
            harness.chapter(preferred, 1.0)
            harness.merge(leading, preferred)
            val model = harness.openDetails(leading)
            withTimeout(30_000) {
                model.state.first { it is NovelDetailsState.Loaded && it.mergeSources.size == 2 }
                model.showManageSourcesDialog()
                val dialog = model.state.first {
                    (it as? NovelDetailsState.Loaded)?.dialog is NovelDetailsDialog.ManageSources
                }
                ((dialog as NovelDetailsState.Loaded).dialog as NovelDetailsDialog.ManageSources)
            } to preferred
        }

        top.first.sources.first().id shouldBe top.second
    }
}
