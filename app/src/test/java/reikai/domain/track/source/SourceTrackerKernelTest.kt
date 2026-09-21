package reikai.domain.track.source

import eu.kanade.tachiyomi.source.SourceTracker
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.entry.EntryId

class SourceTrackerKernelTest {

    private class RecordingTracker(
        override val supportsChapterTracking: Boolean = true,
        override val supportsFavoritesTracking: Boolean = true,
        private val failing: Boolean = false,
    ) : SourceTracker {
        val calls = mutableListOf<String>()

        private fun record(call: String) {
            if (failing) error("site down")
            calls += call
        }

        override suspend fun onChaptersRead(
            manga: SManga,
            changedChapters: List<SChapter>,
            allChapters: List<SChapter>,
            categories: List<String>,
        ) = record("read ${manga.url} ${changedChapters.map { it.url }}")

        override suspend fun onChaptersUnread(
            manga: SManga,
            changedChapters: List<SChapter>,
            allChapters: List<SChapter>,
            categories: List<String>,
        ) = record("unread ${manga.url} ${changedChapters.map { it.url }}")

        override suspend fun onFavorited(manga: SManga, categories: List<String>) =
            record("favorited ${manga.url} $categories")

        override suspend fun onUnfavorited(manga: SManga, categories: List<String>) =
            record("unfavorited ${manga.url}")
    }

    private val entry = EntryId.Novel(1)
    private val tracker = RecordingTracker()

    /** The library as the loader reads it when a call fires; tests change it after queueing an event. */
    private val readIds = mutableMapOf<EntryId, Set<Long>>()
    private val inLibrary = mutableSetOf<EntryId>()
    private val categories = mutableMapOf<EntryId, List<String>>()
    private val failures = mutableListOf<String>()

    private fun tracked(id: EntryId, tracker: SourceTracker) = TrackedEntry(
        tracker = tracker,
        trackerName = "Site",
        manga = SManga.create().apply { url = "e${id.rawId}" },
        favorite = id in inLibrary,
        chapters = (1L..3L).map { chapterId ->
            val chapter = SChapter.create().apply { url = "c$chapterId" }
            TrackedChapter(chapterId, chapter, read = chapterId in readIds[id].orEmpty(), number = chapterId.toDouble())
        },
        categories = categories[id].orEmpty(),
    )

    private fun TestScope.kernel(tracker: SourceTracker = this@SourceTrackerKernelTest.tracker) =
        SourceTrackerKernel(backgroundScope, { tracked(it, tracker) }) { name, _ -> failures += name }

    private fun TestScope.pastDebounce() {
        advanceTimeBy(SourceTrackerKernel.DEBOUNCE_MS + 1)
        runCurrent()
    }

    @Test
    fun `nothing reaches the site before the debounce elapses`() = runTest {
        kernel().chaptersChanged(entry, listOf(1L), read = true)

        advanceTimeBy(SourceTrackerKernel.DEBOUNCE_MS - 1)
        runCurrent()

        tracker.calls.shouldBeEmpty()
    }

    @Test
    fun `reads inside the window go as one call`() = runTest {
        val kernel = kernel()
        kernel.chaptersChanged(entry, listOf(1L), read = true)
        advanceTimeBy(1_000)
        kernel.chaptersChanged(entry, listOf(2L), read = true)

        pastDebounce()

        tracker.calls shouldBe listOf("read e1 [c1, c2]")
    }

    @Test
    fun `an unread inside the window replaces the pending read`() = runTest {
        val kernel = kernel()
        kernel.chaptersChanged(entry, listOf(1L), read = true)
        kernel.chaptersChanged(entry, listOf(2L), read = false)

        pastDebounce()

        tracker.calls shouldBe listOf("unread e1 [c2]")
    }

    @Test
    fun `an unread that leaves a chapter read moves the site back to that one`() = runTest {
        val kernel = kernel()
        kernel.chaptersChanged(entry, listOf(3L), read = false)
        // Read after the event was queued: the call reads the library when it fires, not when queued.
        readIds[entry] = setOf(1L, 2L)

        pastDebounce()

        tracker.calls shouldBe listOf("read e1 [c2]")
    }

    @Test
    fun `a source without chapter tracking is not told about chapters`() = runTest {
        val tracker = RecordingTracker(supportsChapterTracking = false)
        kernel(tracker).chaptersChanged(entry, listOf(1L), read = true)

        pastDebounce()

        tracker.calls.shouldBeEmpty()
    }

    @Test
    fun `an unread counts only the chapters that were read`() = runTest {
        kernel().readStateWritten(
            read = false,
            listOf(ChapterWrite(entry, 1L, wasRead = true), ChapterWrite(entry, 2L, wasRead = false)),
        )

        pastDebounce()

        tracker.calls shouldBe listOf("unread e1 [c1]")
    }

    @Test
    fun `a write across two entries reaches each one's site`() = runTest {
        kernel().readStateWritten(
            read = true,
            listOf(ChapterWrite(entry, 1L, wasRead = false), ChapterWrite(EntryId.Novel(2), 2L, wasRead = false)),
        )

        pastDebounce()

        tracker.calls shouldBe listOf("read e1 [c1]", "read e2 [c2]")
    }

    @Test
    fun `an add goes after the wait with the categories filed during it`() = runTest {
        inLibrary += entry
        kernel().favoriteChanged(entry, favorite = true)
        categories[entry] = listOf("Reading")

        pastDebounce()

        tracker.calls shouldBe listOf("favorited e1 [Reading]")
    }

    @Test
    fun `nothing of an add reaches the site before the wait ends`() = runTest {
        inLibrary += entry
        kernel().favoriteChanged(entry, favorite = true)

        advanceTimeBy(SourceTrackerKernel.DEBOUNCE_MS - 1)
        runCurrent()

        tracker.calls.shouldBeEmpty()
    }

    @Test
    fun `an add that did not stick tells the site nothing`() = runTest {
        kernel().favoriteChanged(entry, favorite = true)

        pastDebounce()

        tracker.calls.shouldBeEmpty()
    }

    @Test
    fun `an add and a remove inside the wait cancel out`() = runTest {
        val kernel = kernel()
        kernel.favoriteChanged(entry, favorite = true)
        kernel.favoriteChanged(entry, favorite = false)

        pastDebounce()

        tracker.calls.shouldBeEmpty()
    }

    @Test
    fun `a source without favorites tracking is not told about favorites`() = runTest {
        val tracker = RecordingTracker(supportsFavoritesTracking = false)
        inLibrary += entry
        kernel(tracker).favoriteChanged(entry, favorite = true)

        pastDebounce()

        tracker.calls.shouldBeEmpty()
    }

    @Test
    fun `a failing site is reported and stops no later call`() = runTest {
        val kernel = kernel(RecordingTracker(failing = true))
        inLibrary += entry
        kernel.favoriteChanged(entry, favorite = true)
        kernel.chaptersChanged(entry, listOf(1L), read = true)

        pastDebounce()

        failures shouldBe listOf("Site", "Site")
    }

    @Test
    fun `a replace migration unfavorites the old entry and passes the target's reads`() = runTest {
        val target = EntryId.Novel(2)
        readIds[target] = setOf(1L, 2L)
        inLibrary += target

        kernel().migrated(entry, target, replace = true, carriedChapters = true)
        pastDebounce()

        tracker.calls shouldBe listOf("unfavorited e1", "favorited e2 []", "read e2 [c1, c2]")
    }

    @Test
    fun `a migration that carried no read state passes no reads`() = runTest {
        val target = EntryId.Novel(2)
        readIds[target] = setOf(1L)
        inLibrary += target

        kernel().migrated(entry, target, replace = false, carriedChapters = false)
        pastDebounce()

        tracker.calls shouldBe listOf("favorited e2 []")
    }
}
