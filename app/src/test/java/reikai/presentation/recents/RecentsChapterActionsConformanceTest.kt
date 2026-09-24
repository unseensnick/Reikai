package reikai.presentation.recents

import eu.kanade.domain.chapter.interactor.SetReadStatus
import eu.kanade.presentation.manga.components.ChapterDownloadAction
import eu.kanade.tachiyomi.data.download.DownloadManager
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadManager
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

/**
 * The recents chapter verbs for both content types, built from the graph rather than a screen's model,
 * so every surface declaring a selection has them. Each case runs both types' real action class over
 * mocked interactors: a merged row reaches every stitched copy, a download names only itself.
 */
class RecentsChapterActionsConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("harnesses")
    fun `marking a merged row read reaches every stitched copy`(harness: ActionsHarness) = runTest {
        harness.actions.markRead(setOf(harness.ref(SELECTED)), read = true)

        harness.markedRead shouldBe setOf(SELECTED, COPY)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("harnesses")
    fun `bookmarking a merged row reaches every stitched copy`(harness: ActionsHarness) = runTest {
        harness.actions.setBookmark(setOf(harness.ref(SELECTED)), bookmarked = true)

        harness.bookmarked shouldBe setOf(SELECTED, COPY)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("harnesses")
    fun `starting a download queues only the named chapter`(harness: ActionsHarness) = runTest {
        // The other type's ref is in the selection too, as a mixed one would carry it.
        harness.actions.download(setOf(harness.ref(SELECTED), harness.foreignRef(OTHER)), ChapterDownloadAction.START)

        harness.queued shouldBe listOf(SELECTED)
    }

    companion object {
        const val ENTRY = 1L
        const val SELECTED = 10L

        /** The same chapter on another source of the group. */
        const val COPY = 20L

        /** A different chapter of the group, which no verb on [SELECTED] may reach. */
        const val NEIGHBOUR = 30L
        const val OTHER = 40L

        val STITCH = listOf(
            ChapterUnit(chapterId = SELECTED, unit = 0, copyOrder = 0),
            ChapterUnit(chapterId = COPY, unit = 0, copyOrder = 1),
            ChapterUnit(chapterId = NEIGHBOUR, unit = 1, copyOrder = 0),
        )

        @JvmStatic
        fun harnesses() = listOf(MangaActionsHarness(), NovelActionsHarness())
    }
}

interface ActionsHarness {
    val actions: RecentsChapterActions
    val markedRead: Set<Long>
    val bookmarked: Set<Long>
    val queued: List<Long>

    fun ref(chapterId: Long): ChapterRef

    /** A ref of the other content type, which this type's actions must leave alone. */
    fun foreignRef(chapterId: Long): ChapterRef
}

class MangaActionsHarness : ActionsHarness {
    override fun toString() = "manga"

    override val markedRead = mutableSetOf<Long>()
    override val bookmarked = mutableSetOf<Long>()
    override val queued = mutableListOf<Long>()

    private fun chapter(id: Long) = Chapter.create().copy(id = id, mangaId = RecentsChapterActionsConformanceTest.ENTRY)

    private val setReadStatus = mockk<SetReadStatus> {
        coEvery { await(any<Boolean>(), *anyVararg<Chapter>()) } answers {
            args.drop(1)
                .flatMap { if (it is Array<*>) it.toList() else listOf(it) }
                .filterIsInstance<Chapter>()
                .forEach { markedRead += it.id }
            SetReadStatus.Result.Success
        }
    }
    private val updateChapter = mockk<UpdateChapter> {
        coEvery { awaitAll(any()) } answers {
            firstArg<List<ChapterUpdate>>().filter { it.bookmark == true }.forEach { bookmarked += it.id }
        }
    }
    private val downloadManager = mockk<DownloadManager>(relaxed = true) {
        every { getQueuedDownloadOrNull(any()) } returns null
        coEvery { downloadChapters(any(), any(), any()) } answers {
            queued += secondArg<List<Chapter>>().map { it.id }
        }
    }
    private val getChapter = mockk<GetChapter> {
        coEvery { await(any<Long>()) } answers { chapter(firstArg()) }
    }
    private val getManga = mockk<GetManga> {
        coEvery { await(any<Long>()) } answers { Manga.create().copy(id = firstArg(), source = 1L) }
    }
    private val sourceManager = mockk<SourceManager> {
        coEvery { get(any<Long>()) } returns mockk()
    }
    private val mergedChapterProvider = mockk<MergedChapterProvider> {
        coEvery { stitchOf(RecentsChapterActionsConformanceTest.ENTRY) } returns
            RecentsChapterActionsConformanceTest.STITCH
    }

    override val actions: RecentsChapterActions = MangaRecentsChapterActions(
        getChapter = getChapter,
        getManga = getManga,
        setReadStatus = setReadStatus,
        updateChapter = updateChapter,
        downloadManager = downloadManager,
        sourceManager = sourceManager,
        mergedChapterProvider = mergedChapterProvider,
    )

    override fun ref(chapterId: Long) = ChapterRef(EntryId.Manga(RecentsChapterActionsConformanceTest.ENTRY), chapterId)

    override fun foreignRef(chapterId: Long) =
        ChapterRef(EntryId.Novel(RecentsChapterActionsConformanceTest.ENTRY), chapterId)
}

class NovelActionsHarness : ActionsHarness {
    override fun toString() = "novel"

    override val markedRead = mutableSetOf<Long>()
    override val bookmarked = mutableSetOf<Long>()
    override val queued = mutableListOf<Long>()

    private fun chapter(id: Long) = NovelChapter(
        id = id, novelId = RecentsChapterActionsConformanceTest.ENTRY, url = "", name = "", read = false,
        bookmark = false, lastTextProgress = 0L, chapterNumber = id.toDouble(), sourceOrder = id,
        dateFetch = 0L, dateUpload = 0L, page = "",
    )

    private val chapterRepository = mockk<NovelChapterRepository> {
        coEvery { getById(any()) } answers { chapter(firstArg()) }
        coEvery { setBookmarkBulk(any(), any()) } answers {
            if (secondArg()) bookmarked += firstArg<List<Long>>()
            true
        }
    }
    private val setNovelReadStatus = mockk<SetNovelReadStatus> {
        coEvery { await(any(), any()) } answers {
            markedRead += secondArg<List<NovelChapter>>().map { it.id }
            SetNovelReadStatus.Result.Success
        }
    }
    private val downloadManager = mockk<NovelDownloadManager>(relaxed = true) {
        every { downloadChapters(any()) } answers { queued += firstArg<List<NovelChapter>>().map { it.id } }
    }
    private val mergedChapterProvider = mockk<NovelMergedChapterProvider> {
        coEvery { stitchOf(RecentsChapterActionsConformanceTest.ENTRY) } returns
            RecentsChapterActionsConformanceTest.STITCH
    }

    override val actions: RecentsChapterActions = NovelRecentsChapterActions(
        chapterRepository = chapterRepository,
        setNovelReadStatus = setNovelReadStatus,
        mergedChapterProvider = mergedChapterProvider,
        downloadManager = { downloadManager },
    )

    override fun ref(chapterId: Long) = ChapterRef(EntryId.Novel(RecentsChapterActionsConformanceTest.ENTRY), chapterId)

    override fun foreignRef(chapterId: Long) =
        ChapterRef(EntryId.Manga(RecentsChapterActionsConformanceTest.ENTRY), chapterId)
}
