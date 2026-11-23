package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.util.resetVirtualDispatchers
import reikai.util.virtualDispatchers
import tachiyomi.domain.manga.model.Manga

/**
 * A reading mode picked just before the manga reader closes still reaches every source of a merged
 * series: the write runs on the app scope, which the close does not end. Rotation, the flag both readers
 * keep, is pinned for both in ReaderViewerFlagsConformanceTest; the novel reading mode is a page layout.
 */
class MangaViewerFlagsCloseTest {

    @BeforeEach
    fun setUp() {
        virtualDispatchers()
    }

    @AfterEach
    fun tearDown() = resetVirtualDispatchers()

    @Test
    fun `a reading mode picked as the reader closes is stored on every source`() = runTest {
        val webtoon = ReadingMode.WEBTOON.flagValue.toLong()
        storedAfterClose(ReadingMode.MASK.toLong(), webtoon) { setMangaReadingMode(ReadingMode.WEBTOON) } shouldBe
            listOf(webtoon, webtoon)
    }

    /** Opens the owner of a two-source group, runs [pick], closes, and answers each source's flag. */
    private suspend fun storedAfterClose(
        mask: Long,
        expected: Long,
        pick: eu.kanade.tachiyomi.ui.reader.ReaderViewModel.() -> Unit,
    ): List<Long> = MangaReaderViewModelHarness.create().use { harness ->
        val owner = harness.manga(1L, source = 100L, title = "Owner")
        val sibling = harness.manga(2L, source = 200L, title = "Sibling")
        val pooled = listOf(harness.chapter(11L, owner, 1.0), harness.chapter(21L, sibling, 2.0))
        val group = MergedChapterProvider.Group(
            mangaById = mapOf(owner.id to owner, sibling.id to sibling),
            chapters = pooled,
            sourceNameByMangaId = mapOf(owner.id to "Owner", sibling.id to "Sibling"),
            stitch = pooled.mapIndexed { unit, chapter ->
                ChapterUnit(chapterId = chapter.id, unit = unit, copyOrder = 0)
            },
            pooledChapters = pooled,
        )
        // The sibling's write is still running when the reader closes.
        harness.open(owner, chapterId = 11L, group = group, slowFlagWrite = { id, _ -> id == sibling.id }) { model, _ ->
            model.pick()
            // Into the write loop, past the owner's write, so the close lands while the sibling's still runs.
            settled(harness, owner, mask, expected)
        }
        listOf(owner, sibling).map { manga -> settled(harness, manga, mask, expected) }
    }

    /** [manga]'s stored flag under [mask], once the writes have landed or [expected] has been waited for. */
    private suspend fun settled(harness: MangaReaderViewModelHarness, manga: Manga, mask: Long, expected: Long): Long {
        val read = suspend { harness.storedManga(manga.id).viewerFlags and mask }
        withTimeoutOrNull(15_000) { while (read() != expected) delay(20) }
        return read()
    }
}
