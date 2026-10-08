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
import reikai.domain.download.MangaChapterDownloadActions
import reikai.domain.entry.EntryId
import reikai.domain.manga.MergedChapterProvider
import reikai.domain.merge.ChapterUnit
import reikai.domain.merge.MergeScope
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergedChapterProvider
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.novel.download.NovelDownloadManager
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.UpdateChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.model.ChapterUpdate
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.model.StubSource
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
        harness.actions.download(
            setOf(harness.ref(SELECTED), harness.foreignRef(OTHER)),
            ChapterDownloadAction.START,
            MergeScope.Source,
        )

        harness.queued shouldBe listOf(SELECTED)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("harnesses")
    fun `deleting an Updates row's download removes only its own copy`(harness: ActionsHarness) = runTest {
        harness.actions.deleteDownloads(setOf(harness.ref(SELECTED)), MergeScope.Source)

        harness.deleted shouldBe setOf(SELECTED)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("harnesses")
    fun `deleting a History row's download removes every stitched copy`(harness: ActionsHarness) = runTest {
        harness.actions.deleteDownloads(setOf(harness.ref(SELECTED)), MergeScope.Group)

        harness.deleted shouldBe setOf(SELECTED, COPY)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("harnesses")
    fun `a row's own delete control follows the row's scope`(harness: ActionsHarness) = runTest {
        harness.actions.download(setOf(harness.ref(SELECTED)), ChapterDownloadAction.DELETE, MergeScope.Group)

        harness.deleted shouldBe setOf(SELECTED, COPY)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingSourceHarnesses")
    fun `a History row from a missing source downloads the installed source's copy`(harness: ActionsHarness) = runTest {
        harness.actions.download(setOf(harness.ref(SELECTED)), ChapterDownloadAction.START, MergeScope.Group)

        harness.queued shouldBe listOf(COPY)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingSourceHarnesses")
    fun `a History row only a missing source holds downloads nothing`(harness: ActionsHarness) = runTest {
        harness.actions.download(setOf(harness.ref(NEIGHBOUR)), ChapterDownloadAction.START, MergeScope.Group)

        harness.queued shouldBe emptyList()
    }

    companion object {
        const val ENTRY = 1L

        /** The entry [COPY] belongs to, whose source is always installed. */
        const val INSTALLED_ENTRY = 2L
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

        /** [ENTRY]'s source is not installed. */
        @JvmStatic
        fun missingSourceHarnesses() = listOf(
            MangaActionsHarness(entryMissing = true),
            NovelActionsHarness(entryMissing = true),
        )

        fun ownerOf(chapterId: Long) = if (chapterId == COPY) INSTALLED_ENTRY else ENTRY
    }
}

interface ActionsHarness {
    val actions: RecentsChapterActions
    val markedRead: Set<Long>
    val bookmarked: Set<Long>
    val queued: List<Long>
    val deleted: Set<Long>

    fun ref(chapterId: Long): ChapterRef

    /** A ref of the other content type, which this type's actions must leave alone. */
    fun foreignRef(chapterId: Long): ChapterRef
}

class MangaActionsHarness(private val entryMissing: Boolean = false) : ActionsHarness {
    override fun toString() = "manga"

    override val markedRead = mutableSetOf<Long>()
    override val bookmarked = mutableSetOf<Long>()
    override val queued = mutableListOf<Long>()
    override val deleted = mutableSetOf<Long>()

    private fun chapter(id: Long) = Chapter.create().copy(
        id = id,
        mangaId = RecentsChapterActionsConformanceTest.ownerOf(id),
    )

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
        every { deleteChapters(any(), any(), any()) } answers {
            deleted += firstArg<List<Chapter>>().map { it.id }
        }
    }
    private val getChapter = mockk<GetChapter> {
        coEvery { await(any<Long>()) } answers { chapter(firstArg()) }
    }
    private val getManga = mockk<GetManga> {
        coEvery { await(any<Long>()) } answers { Manga.create().copy(id = firstArg(), source = firstArg()) }
    }

    // A manga's source id is its own id here.
    private val sourceManager = mockk<SourceManager> {
        coEvery { getOrStub(any()) } answers {
            if (entryMissing && firstArg<Long>() == RecentsChapterActionsConformanceTest.ENTRY) {
                StubSource(firstArg(), "en", "gone")
            } else {
                mockk()
            }
        }
    }
    private val mergedChapterProvider = mockk<MergedChapterProvider> {
        coEvery { stitchOf(RecentsChapterActionsConformanceTest.ENTRY) } returns
            RecentsChapterActionsConformanceTest.STITCH
    }

    override val actions: RecentsChapterActions = MangaRecentsChapterActions(
        getChapter = getChapter,
        setReadStatus = setReadStatus,
        updateChapter = updateChapter,
        mergedChapterProvider = mergedChapterProvider,
        downloadActions = MangaChapterDownloadActions(downloadManager, getManga, sourceManager),
        getManga = getManga,
        sourceManager = sourceManager,
    )

    override fun ref(chapterId: Long) = ChapterRef(EntryId.Manga(RecentsChapterActionsConformanceTest.ENTRY), chapterId)

    override fun foreignRef(chapterId: Long) =
        ChapterRef(EntryId.Novel(RecentsChapterActionsConformanceTest.ENTRY), chapterId)
}

class NovelActionsHarness(private val entryMissing: Boolean = false) : ActionsHarness {
    override fun toString() = "novel"

    override val markedRead = mutableSetOf<Long>()
    override val bookmarked = mutableSetOf<Long>()
    override val queued = mutableListOf<Long>()
    override val deleted = mutableSetOf<Long>()

    private fun chapter(id: Long) = NovelChapter(
        id = id, novelId = RecentsChapterActionsConformanceTest.ownerOf(id), url = "", name = "", read = false,
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
        coEvery { downloadChapters(any()) } answers { queued += firstArg<List<NovelChapter>>().map { it.id } }
        every { deleteChapters(any()) } answers { deleted += firstArg<List<NovelChapter>>().map { it.id } }
    }
    private val mergedChapterProvider = mockk<NovelMergedChapterProvider> {
        coEvery { stitchOf(RecentsChapterActionsConformanceTest.ENTRY) } returns
            RecentsChapterActionsConformanceTest.STITCH
    }

    // A novel's source is named after its id here.
    private val sources = mockk<NovelSourceManager>().also { sources ->
        coEvery { sources.get(any()) } answers {
            val missing = "src${RecentsChapterActionsConformanceTest.ENTRY}"
            if (entryMissing && firstArg<String>() == missing) null else mockk<NovelSource>()
        }
    }

    override val actions: RecentsChapterActions = NovelRecentsChapterActions(
        chapterRepository = chapterRepository,
        setNovelReadStatus = setNovelReadStatus,
        mergedChapterProvider = mergedChapterProvider,
        downloadManager = { downloadManager },
        novelRepository = mockk {
            coEvery { getById(any()) } answers
                { Novel.create().copy(id = firstArg(), source = "src${firstArg<Long>()}") }
        },
        sourceManager = sources,
    )

    override fun ref(chapterId: Long) = ChapterRef(EntryId.Novel(RecentsChapterActionsConformanceTest.ENTRY), chapterId)

    override fun foreignRef(chapterId: Long) =
        ChapterRef(EntryId.Manga(RecentsChapterActionsConformanceTest.ENTRY), chapterId)
}
