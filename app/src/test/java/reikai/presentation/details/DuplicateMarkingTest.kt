package reikai.presentation.details

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.library.ContentType
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
}
