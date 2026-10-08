package reikai.domain.chapter

import eu.kanade.domain.manga.model.downloadedFilter
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.manga.ChapterList
import eu.kanade.tachiyomi.util.chapter.getNextUnread
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.merge.GroupMarks
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelChapterSettings
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNextNovelChapter
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.presentation.novel.details.novelShownRows
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga

/** Where one content type's resume opens, on one surface, given which chapter urls the user hid. */
class ResumeCase(private val label: String, val resume: suspend (hiddenUrls: Set<String>) -> Long?) {
    override fun toString() = label
}

/**
 * The library continue button and a series page's Resume over both content types, with chapters 1 and
 * 2 unread. A hidden chapter is passed over while another is unread. When every unread chapter is
 * hidden the first of them still opens, so neither button goes dead.
 */
class HiddenChapterResumeConformanceTest {

    @BeforeEach
    fun setUp() {
        // The downloaded filter reads a global preference through the app graph; it is not under test.
        mockkStatic(DOWNLOADED_FILTER_FILE)
        every { any<Manga>().downloadedFilter } returns TriState.DISABLED
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(DOWNLOADED_FILTER_FILE)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a hidden chapter is passed over for the next unread`(case: ResumeCase) = runTest {
        case.resume(setOf("u1")) shouldBe 2L
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `the first hidden chapter still opens when nothing else is unread`(case: ResumeCase) = runTest {
        case.resume(setOf("u1", "u2")) shouldBe 1L
    }

    @Test
    fun `the key matches what hiding a chapter has always stored`() {
        // Users' hidden sets already hold keys in this shape; any other would unhide every chapter.
        hiddenChapterKey("7", "/chapter/1") shouldBe "7|/chapter/1"
    }

    companion object {
        private const val DOWNLOADED_FILTER_FILE = "eu.kanade.domain.manga.model.MangaKt"

        private val manga = Manga.create().copy(id = 1L, source = 7L)
        private val mangaChapters = listOf(1L, 2L).map {
            Chapter.create().copy(
                id = it,
                mangaId = 1L,
                url = "u$it",
                chapterNumber = it.toDouble(),
                // A manga source lists newest first, so chapter 1 is read first.
                sourceOrder = 10L - it,
            )
        }
        private val novel = Novel.create().copy(id = 1L, source = "ln-source")
        private val novelChapters = listOf(1L, 2L).map {
            NovelChapter(
                id = it,
                novelId = 1L,
                url = "u$it",
                name = "Ch $it",
                read = false,
                bookmark = false,
                lastTextProgress = 0L,
                chapterNumber = it.toDouble(),
                sourceOrder = it,
                dateFetch = 0L,
                dateUpload = 0L,
                page = "",
            )
        }

        private fun mangaKeys(hiddenUrls: Set<String>) = hiddenUrls.mapTo(HashSet()) { hiddenChapterKey("7", it) }

        private fun novelKeys(hiddenUrls: Set<String>) =
            hiddenUrls.mapTo(HashSet()) { hiddenChapterKey("ln-source", it) }

        private val mangaLibrary = ResumeCase("manga library") { hiddenUrls ->
            mangaChapters.getNextUnread(manga, mockk(), hiddenKeys = mangaKeys(hiddenUrls))?.id
        }

        private val mangaDetails = ResumeCase("manga details") { hiddenUrls ->
            val hiddenKeys = mangaKeys(hiddenUrls)
            mangaChapters.map {
                ChapterList.Item(it, Download.State.NOT_DOWNLOADED, 0, isRead = false, isBookmarked = false)
            }
                .getNextUnread(manga) { it.chapter.hiddenKey(manga) in hiddenKeys }?.id
        }

        private val novelLibrary = ResumeCase("novel library") { hiddenUrls ->
            val preferences = NovelPreferences(
                InMemoryPreferenceStore(
                    sequenceOf(
                        InMemoryPreferenceStore.InMemoryPreference(
                            "novel_hidden_chapters",
                            novelKeys(hiddenUrls),
                            emptySet(),
                        ),
                    ),
                ),
            )
            val chapterRepository = mockk<NovelChapterRepository> { coEvery { getByNovelId(1L) } returns novelChapters }
            val novelRepository = mockk<NovelRepository> { coEvery { getById(1L) } returns novel }
            val mergeManager = mockk<NovelMergeManager> { coEvery { computeRelatedIds(1L) } returns longArrayOf(1L) }
            GetNextNovelChapter(
                chapterRepository,
                novelRepository,
                preferences,
                mergeManager,
                mockk(),
                NovelChapterSettings(novelRepository),
            )
                .awaitFirstUnreadInGroup(1L, downloadedOnly = false) { _, _ -> emptySet() }?.id
        }

        private val novelDetails = ResumeCase("novel details") { hiddenUrls ->
            novelShownRows(
                novelChapters,
                novel,
                NovelPreferences(InMemoryPreferenceStore()),
                hiddenKeys = novelKeys(hiddenUrls),
                showHiddenRequested = false,
                keyOf = { hiddenChapterKey("ln-source", it.url) },
                downloadedChapterIds = emptySet(),
                marks = GroupMarks.NONE,
                downloadedOnly = false,
            ).resume?.id
        }

        @JvmStatic
        fun cases() = listOf(mangaLibrary, novelLibrary, mangaDetails, novelDetails)
    }
}
