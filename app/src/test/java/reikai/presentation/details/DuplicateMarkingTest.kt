package reikai.presentation.details

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.library.ContentType
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.core.common.preference.InMemoryPreferenceStore

/** A screen builds a marking per composition entry over the model's scope, which outlives each one. */
class DuplicateMarkingTest {

    private val repository = InMemoryChapterCopyMarks()
    private val store = InMemoryPreferenceStore()

    @Test
    fun `the marks stop being read when the last collector leaves`() = runTest {
        val marking = DuplicateMarking(
            ContentType.MANGA,
            repository,
            store.getBoolean("show_skipped_duplicates", true),
            store.getBoolean("skip_dupe", true),
            backgroundScope,
            backgroundScope,
        )
        val collector = backgroundScope.launch { marking.marks.collect {} }
        runCurrent()
        repository.marks(ContentType.MANGA).subscriptionCount.first { it == 1 }

        collector.cancel()
        runCurrent()

        repository.marks(ContentType.MANGA).subscriptionCount.value shouldBe 0
    }

    /** With either switch off the reader skips no copies, so the list marks none. */
    @ParameterizedTest(name = "show {0}, reader skips {1}")
    @CsvSource("true, false, false", "false, true, false", "true, true, true")
    fun `the list marks copies only while it shows them and the reader skips them`(
        show: Boolean,
        skip: Boolean,
        marked: Boolean,
    ) = runTest {
        val marking = DuplicateMarking(
            ContentType.MANGA,
            repository,
            store.getBoolean("show_skipped_duplicates", show),
            store.getBoolean("skip_dupe", skip),
            backgroundScope,
            backgroundScope,
        )
        backgroundScope.launch { marking.marks.collect {} }
        runCurrent()

        (marking.marks.value != null) shouldBe marked
    }

    @Test
    fun `marking a selection across two sources groups only the source with more than one chapter`() = runTest {
        virtualDispatchers(StandardTestDispatcher(testScheduler))
        try {
            val marking = DuplicateMarking(
                ContentType.MANGA,
                repository,
                store.getBoolean("show_skipped_duplicates", true),
                store.getBoolean("skip_dupe", true),
                backgroundScope,
                this,
            )
            marking.markAsCopies(
                listOf(
                    MarkableChapter(ownerId = 1L, url = "/a"),
                    MarkableChapter(ownerId = 1L, url = "/b"),
                    MarkableChapter(ownerId = 2L, url = "/c"),
                ),
            )
            advanceUntilIdle()

            val grouped = repository.marks(ContentType.MANGA).value.mapValues { it.value.keys }
            grouped shouldBe mapOf(1L to setOf("/a", "/b"))
        } finally {
            resetVirtualDispatchers()
        }
    }

    /** Copies never cross entries, so one chapter from each of two merged sources marks nothing. */
    @Test
    fun `one chapter from each source of a merge cannot be marked as copies`() {
        noDuplicateMarking().canMarkAsCopies(
            listOf(MarkableChapter(ownerId = 1L, url = "/a"), MarkableChapter(ownerId = 2L, url = "/b")),
        ) shouldBe false
    }

    @Test
    fun `two chapters of one source can be marked as copies`() {
        noDuplicateMarking().canMarkAsCopies(
            listOf(MarkableChapter(ownerId = 1L, url = "/a"), MarkableChapter(ownerId = 1L, url = "/b")),
        ) shouldBe true
    }
}
