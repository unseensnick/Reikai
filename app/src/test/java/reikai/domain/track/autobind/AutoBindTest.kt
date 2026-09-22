package reikai.domain.track.autobind

import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.source.Source
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.model.Novel
import reikai.novel.source.NovelSource
import tachiyomi.domain.manga.model.Manga

/**
 * Binding on add, pinned once for both content types: [bindOnAdd] is the one routine manga and novels
 * both run, so each case below runs with a manga entry and a novel one.
 */
class AutoBindTest {

    private class FakeTracker(
        name: String,
        private val accepted: Boolean = true,
        private val match: TrackSearch? = TrackSearch.create(1L),
        private val failing: Boolean = false,
    ) : AutoBindTracker {
        override val tracker: Tracker = mockk { every { this@mockk.name } returns name }

        override fun accepts(entry: AutoBindEntry) = accepted

        override suspend fun match(entry: AutoBindEntry): TrackSearch? {
            if (failing) error("server down")
            return match
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `only trackers that know the entry's source are asked`(kind: String) = runTest {
        val entry = entryOf(kind)
        val bound = mutableListOf<String>()

        bindOnAdd(entry, listOf(FakeTracker("A"), FakeTracker("B", accepted = false))) { candidate, _ ->
            bound += candidate.tracker.name
        }

        bound shouldBe listOf("A")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `a tracker that fails stops none of the others`(kind: String) = runTest {
        val entry = entryOf(kind)
        val bound = mutableListOf<String>()

        bindOnAdd(entry, listOf(FakeTracker("A", failing = true), FakeTracker("B"))) { candidate, _ ->
            bound += candidate.tracker.name
        }

        bound shouldBe listOf("B")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `a bind that fails stops none of the others`(kind: String) = runTest {
        val entry = entryOf(kind)
        val bound = mutableListOf<String>()

        bindOnAdd(entry, listOf(FakeTracker("A"), FakeTracker("B"))) { candidate, _ ->
            if (candidate.tracker.name == "A") error("insert failed")
            bound += candidate.tracker.name
        }

        bound shouldBe listOf("B")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("entries")
    fun `no match binds nothing`(kind: String) = runTest {
        val entry = entryOf(kind)
        val bound = mutableListOf<String>()

        bindOnAdd(entry, listOf(FakeTracker("A", match = null))) { candidate, _ -> bound += candidate.tracker.name }

        bound shouldBe emptyList()
    }

    @Test
    fun `a manga server's tracker knows the manga from its own source`() {
        enhanced(accepts = true).accepts(mangaEntry()) shouldBe true
    }

    @Test
    fun `a manga server's tracker does not know the manga from another source`() {
        enhanced(accepts = false).accepts(mangaEntry()) shouldBe false
    }

    @Test
    fun `a manga server's tracker never takes a novel`() {
        enhanced(accepts = true).accepts(novelEntry()) shouldBe false
    }

    @Test
    fun `a manga server's tracker matches through its own lookup`() = runTest {
        val match = TrackSearch.create(7L)

        enhanced(accepts = true, found = match).match(mangaEntry()) shouldBe match
    }

    private fun enhanced(accepts: Boolean, found: TrackSearch? = null): EnhancedAutoBind {
        // Stubbed outside the mockk block: a bare match(any()) in there binds to MockK's own match.
        val server = mockk<EnhancedTracker>()
        every { server.accept(any()) } returns accepts
        coEvery { server.match(any()) } returns found
        return EnhancedAutoBind(mockk(), server)
    }

    companion object {
        private fun mangaEntry() = AutoBindEntry.Manga(Manga.create().copy(title = "a manga"), mockk<Source>())

        private fun novelEntry() = AutoBindEntry.Novel(Novel.create().copy(title = "a novel"), mockk<NovelSource>())

        private fun entryOf(kind: String) = if (kind == "manga") mangaEntry() else novelEntry()

        @JvmStatic
        fun entries() = listOf("manga", "novel")
    }
}
