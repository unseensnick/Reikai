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
import reikai.domain.novel.LnSourceIdentity
import reikai.presentation.reader.FakeNovelSource
import reikai.presentation.reader.NovelReaderViewModelHarness

class NovelDetailsMergeChipsTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    /** A manga member's chip shows its stub's stored name, so a novel member's keeps its last seen one. */
    @Test
    fun `a merged member whose source is uninstalled is named as it was last seen`() = runTest {
        val names = NovelReaderViewModelHarness.create(testScheduler).use { harness ->
            harness.novelPreferences.seenNovelSources().set(mapOf("gone" to LnSourceIdentity(name = "Old Site")))
            val leading = harness.novel(harness.source("alpha"))
            val other = harness.novel(FakeNovelSource("gone", "unregistered"))
            harness.merge(leading, other)
            val model = harness.openDetails(leading)
            withContext(Dispatchers.Default) {
                withTimeout(30_000) {
                    model.state.first { it is NovelDetailsState.Loaded && it.mergeSources.size == 2 }
                        .let { (it as NovelDetailsState.Loaded).mergeSources.map { chip -> chip.sourceName } }
                }
            }
        }

        names.toSet() shouldBe setOf("alpha", "Old Site")
    }
}
