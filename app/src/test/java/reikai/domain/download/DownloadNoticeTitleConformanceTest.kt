package reikai.domain.download

import android.app.Application
import android.content.Context
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.DownloadNotifier
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.NetworkState
import eu.kanade.tachiyomi.util.system.activeNetworkState
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import reikai.domain.source.ReikaiSourcePreferences
import reikai.novel.download.FakeSharedPreferences
import reikai.novel.download.NovelDownloadManager
import reikai.novel.download.NovelDownloadNotifier
import reikai.novel.download.NovelDownloadWorker
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
import reikai.util.realTimeSourceContacts
import reikai.util.testAppScope
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga
import kotlin.time.Instant
import mihon.icons.materialsymbols.R as MaterialSymbolsR

/**
 * The download progress and error notices name the entry by its Edit info title, over each engine's real
 * notifier. The chapter files are still named by the source title, so only what the user reads changes. The
 * progress notice offers both types the same buttons.
 */
class DownloadNoticeTitleConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `the progress notice names the entry by its Edit info title`(half: NoticeTitleHalf) = runTest {
        half.notices().progress shouldContain MINE
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `the error notice names the entry by its Edit info title`(half: NoticeTitleHalf) = runTest {
        half.notices().errorTitle shouldBe "$MINE: $CHAPTER_NAME"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `the progress notice offers Pause and Show entry`(half: NoticeTitleHalf) = runTest {
        half.notices().progressActions shouldBe
            listOf(MaterialSymbolsR.drawable.rounded_filled_pause, MaterialSymbolsR.drawable.rounded_book)
    }

    /**
     * The progress notice's title and text together, since each engine puts the entry in a different one, and the
     * icons of its buttons.
     */
    data class Notices(val progress: String, val errorTitle: String?, val progressActions: List<Int>)

    companion object {
        const val ENTRY = 1L
        const val CHAPTER = 10L
        const val SOURCE_TITLE = "Source title"
        const val MINE = "Mine"
        const val CHAPTER_NAME = "Ch 10"

        @JvmStatic
        fun halves() = listOf(MangaNoticeTitleHalf(), NovelNoticeTitleHalf())
    }
}

interface NoticeTitleHalf {
    /** Downloads one chapter of an entry with an Edit info title, which fails; what the shade shows. */
    suspend fun notices(): DownloadNoticeTitleConformanceTest.Notices
}

private fun FakeNotificationShade.wordsOf(id: Int) = "${titleOf(id)} ${textOf(id)}"

class MangaNoticeTitleHalf : NoticeTitleHalf {
    override fun toString() = "manga"

    override suspend fun notices(): DownloadNoticeTitleConformanceTest.Notices = FakeNotificationShade().use { shade ->
        val id = DownloadNoticeTitleConformanceTest.ENTRY
        val manga = Manga.create().copy(id = id, title = DownloadNoticeTitleConformanceTest.SOURCE_TITLE)
        val notifier = DownloadNotifier(
            mockk<Application>(relaxed = true),
            SecurityPreferences(InMemoryPreferenceStore()),
            mockk {
                coEvery { await(EntryId.Manga(id)) } returns
                    CustomMangaInfo(mangaId = id, title = DownloadNoticeTitleConformanceTest.MINE)
            },
        ) {
            mockk { coEvery { adultIdsAmong(any()) } returns emptySet() }
        }
        val chapter = Chapter.create().copy(
            id = DownloadNoticeTitleConformanceTest.CHAPTER,
            mangaId = id,
            name = DownloadNoticeTitleConformanceTest.CHAPTER_NAME,
        )

        notifier.onProgressChange(Download(mockk(), manga, chapter).apply { pages = emptyList() })
        notifier.onError("error", chapter.name, manga)

        DownloadNoticeTitleConformanceTest.Notices(
            progress = shade.wordsOf(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS),
            errorTitle = shade.titleOf(Notifications.ID_DOWNLOAD_CHAPTER_ERROR)?.toString(),
            progressActions = shade.actionIconsOf(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS),
        )
    }
}

/** The real drain hands the worker's notifier what it shows, as NovelDownloadWorker does. */
class NovelNoticeTitleHalf : NoticeTitleHalf {
    override fun toString() = "novel"

    override suspend fun notices(): DownloadNoticeTitleConformanceTest.Notices = FakeNotificationShade().use { shade ->
        val id = DownloadNoticeTitleConformanceTest.ENTRY
        val novel = Novel.create().copy(
            id = id,
            source = "src",
            title = DownloadNoticeTitleConformanceTest.SOURCE_TITLE,
        )
        val chapter = NovelChapter(
            id = DownloadNoticeTitleConformanceTest.CHAPTER,
            novelId = id,
            url = "u",
            name = DownloadNoticeTitleConformanceTest.CHAPTER_NAME,
            read = false,
            bookmark = false,
            lastTextProgress = 0L,
            chapterNumber = 10.0,
            sourceOrder = 10L,
            dateFetch = Instant.fromEpochMilliseconds(0L),
            dateUpload = null,
            page = "",
        )
        // An empty chapter fails at once, with no retries to wait out.
        val source = mockk<NovelSource> {
            every { minimumRequestDelayMs } returns 0L
            coEvery { parseChapter(any()) } returns ""
        }
        val context = mockk<Context>(relaxed = true) {
            every { getSharedPreferences(any(), any()) } returns FakeSharedPreferences()
        }
        mockkObject(NovelDownloadWorker.Companion)
        mockkStatic(Context::activeNetworkState)
        try {
            every { NovelDownloadWorker.start(any()) } just runs
            every { any<Context>().activeNetworkState() } returns NetworkState(true, true, true)
            val securityPreferences = SecurityPreferences(InMemoryPreferenceStore())
            val manager = NovelDownloadManager(
                scope = testAppScope(),
                context = context,
                provider = mockk { every { availableSpace() } returns -1L },
                cache = mockk { every { downloadedChapterIds(novel, any()) } returns emptySet() },
                chapterRepo = mockk { coEvery { getById(chapter.id) } returns chapter },
                novelRepo = mockk<NovelRepository> { coEvery { getById(id) } returns novel },
                sourceManager = mockk<NovelSourceManager>().also { coEvery { it.get(novel.source) } returns source },
                installer = mockk { coEvery { ensureLoaded() } just runs },
                downloadPreferences = DownloadPreferences(InMemoryPreferenceStore()),
                sourcePreferences = ReikaiSourcePreferences(InMemoryPreferenceStore()),
                novelPreferences = NovelPreferences(InMemoryPreferenceStore()),
                saver = mockk(),
                securityPreferences = securityPreferences,
                adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
                sourceTitles = mockk(),
                getEntryCustomInfo = mockk {
                    coEvery { await(EntryId.Novel(id)) } returns
                        CustomNovelInfo(novelId = id, title = DownloadNoticeTitleConformanceTest.MINE)
                },
                sourceContacts = realTimeSourceContacts(),
            )
            val notifier = NovelDownloadNotifier(context, securityPreferences)
            manager.downloadChapters(listOf(chapter))

            manager.runQueue(onProgress = notifier::show, onError = notifier::onError)

            DownloadNoticeTitleConformanceTest.Notices(
                progress = shade.wordsOf(Notifications.ID_NOVEL_DOWNLOADER),
                errorTitle = shade.titleOf(Notifications.ID_NOVEL_DOWNLOADER_ERROR)?.toString(),
                progressActions = shade.actionIconsOf(Notifications.ID_NOVEL_DOWNLOADER),
            )
        } finally {
            unmockkObject(NovelDownloadWorker.Companion)
            unmockkStatic(Context::activeNetworkState)
        }
    }
}
