package reikai.presentation.reader

import eu.kanade.tachiyomi.ui.reader.loader.DownloadPageLoader
import io.kotest.matchers.shouldBe
import io.mockk.mockk
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Download-ahead on a merged manga read in group scope skips what is read now, not what was read when
 * the reader opened: the novel reader reads the group fresh before it picks, and so does this.
 */
class MangaDownloadAheadTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(StandardTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `chapters marked read since the reader opened are not queued again`() = runTest {
        MangaReaderViewModelHarness.create().use { harness ->
            val owner = harness.manga(1L, 100L, "Owner", chapterFlags = Manga.CHAPTER_SORTING_NUMBER)
            val sibling = harness.manga(2L, 200L, "Sibling")
            val chapters = (1..8).map { harness.chapter(10L + it, owner, it.toDouble()) }
            val group = MergedChapterProvider.Group(
                mangaById = mapOf(owner.id to owner, sibling.id to sibling),
                chapters = chapters,
                sourceNameByMangaId = mapOf(owner.id to "Owner", sibling.id to "Sibling"),
                stitch = chapters.mapIndexed { index, chapter -> ChapterUnit(chapter.id, unit = index, copyOrder = 0) },
                pooledChapters = chapters,
            )
            val queued = ConcurrentHashMap.newKeySet<Long>()
            harness.open(
                owner,
                chapterId = 11L,
                group = group,
                preferences = mapOf("auto_download_while_reading" to 3),
                onDisk = setOf(11L, 12L),
                onDownload = { downloaded -> downloaded.forEach { queued += it.id } },
            ) { model, state ->
                // As the chapter sheet's mark read writes them, behind the chapters loaded at open.
                listOf(13L, 14L, 15L).forEach { harness.setRead(it, read = true) }
                val current = state.viewerChapters!!.currChapter
                val pages = loadedPages(current, 2)
                // Download-ahead only runs while the chapter being read is itself read from disk.
                current.pageLoader = mockk<DownloadPageLoader>(relaxed = true)
                model.onPageSelected(pages[1])
                withContext(Dispatchers.Default) {
                    withTimeoutOrNull(3_000) { while (queued.isEmpty()) delay(20) }
                    delay(300)
                }
                queued.toSet()
            // The next chapter, already on disk, counts as one of the three, as upstream's slice does.
            } shouldBe setOf(12L, 16L, 17L)
        }
    }
}
