package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import tachiyomi.domain.manga.model.Manga

/**
 * A reader flag picked just before the manga reader closes still reaches every source of a merged
 * series, as the novel reader's flag saves do: the write runs on the app scope, which the close does not
 * end. Each case picks a flag and lets the reader close straight away.
 */
class MangaViewerFlagsCloseTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a reading mode picked as the reader closes is stored on every source`() = runTest {
        val webtoon = ReadingMode.WEBTOON.flagValue.toLong()
        storedAfterClose(ReadingMode.MASK.toLong(), webtoon) { setMangaReadingMode(ReadingMode.WEBTOON) } shouldBe
            listOf(webtoon, webtoon)
    }

    @Test
    fun `a rotation picked as the reader closes is stored on every source`() = runTest {
        val reverse = ReaderOrientation.REVERSE_PORTRAIT.flagValue.toLong()
        storedAfterClose(ReaderOrientation.MASK.toLong(), reverse) {
            setMangaOrientationType(ReaderOrientation.REVERSE_PORTRAIT)
        } shouldBe listOf(reverse, reverse)
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
        harness.open(owner, chapterId = 11L, group = group, slowFlagWrites = setOf(sibling.id)) { model, _ ->
            model.pick()
            // Into the write loop, past the owner's write.
            withContext(Dispatchers.Default) { delay(100) }
        }
        listOf(owner, sibling).map { manga -> settled(harness, manga, mask, expected) }
    }

    private suspend fun settled(harness: MangaReaderViewModelHarness, manga: Manga, mask: Long, expected: Long) =
        withContext(Dispatchers.Default) {
            val read = suspend { harness.storedManga(manga.id).viewerFlags and mask }
            withTimeoutOrNull(5_000) { while (read() != expected) delay(20) }
            read()
        }
}
