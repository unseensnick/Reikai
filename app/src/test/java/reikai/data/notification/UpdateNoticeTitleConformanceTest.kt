package reikai.data.notification

import android.app.Application
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.library.LibraryUpdateNotifier
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.notify
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkConstructor
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.data.novel.update.NovelUpdateNotifier
import reikai.domain.download.FakeNotificationShade
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.core.common.i18n.pluralStringResource
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga

/** Both library updaters name an entry by its Edit info title, over each updater's real notifier. */
class UpdateNoticeTitleConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `the progress notice names the entry by its Edit info title`(
        @Suppress("UNUSED_PARAMETER") name: String,
        half: () -> UpdateNoticeHalf,
    ) = half().use {
        runTest {
            it.showProgress()

            it.progressText() shouldBe "Mine"
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("halves")
    fun `the new-chapters summary names a sole updated entry by its Edit info title`(
        @Suppress("UNUSED_PARAMETER") name: String,
        half: () -> UpdateNoticeHalf,
    ) = half().use {
        runTest {
            it.showNewChapters()

            it.summaryText() shouldBe "Mine"
        }
    }

    companion object {
        // Factories, since a half's mocks are global and the last one built would answer for both.
        @JvmStatic
        fun halves() = listOf(
            Arguments.of("manga", { MangaUpdateNoticeHalf() }),
            Arguments.of("novel", { NovelUpdateNoticeHalf() }),
        )
    }
}

/** One updater's notifier over a fake shade, with its single entry renamed "Mine" in Edit info. */
abstract class UpdateNoticeHalf : AutoCloseable {

    protected val shade = FakeNotificationShade()
    protected val context = mockk<Application>(relaxed = true)
    protected val security = SecurityPreferences(InMemoryPreferenceStore())

    init {
        // The manga updater posts its per-entry notices from the main thread, which runTest then drives.
        Dispatchers.setMain(StandardTestDispatcher())
        mockkStatic(MAIN_INTENT, NEW_CHAPTERS)
        every { mainActivityPendingIntent(any(), any(), any(), any()) } returns mockk()
        coEvery { any<Context>().notificationCover(any()) } returns null
        every { any<Context>().pluralStringResource(any(), any(), *anyVararg()) } returns "text"
        every { any<Context>().notify(any<List<NotificationManagerCompat.NotificationWithIdAndTag>>()) } just runs
        with(NotificationReceiver.Companion) {
            every { cancelLibraryUpdatePendingBroadcast(any()) } returns mockk()
            every { openChapterPendingActivity(any(), any(), any<Chapter>()) } returns mockk()
            every { openChapterPendingActivity(any(), any(), any<Int>()) } returns mockk()
            every { markAsReadPendingBroadcast(any(), any(), any(), any()) } returns mockk()
            every { downloadChaptersPendingBroadcast(any(), any(), any(), any()) } returns mockk()
            every { cancelNovelLibraryUpdatePendingBroadcast(any()) } returns mockk()
            every { openNovelChapterPendingActivity(any(), any(), any()) } returns mockk()
            every { markNovelAsReadPendingBroadcast(any(), any(), any(), any()) } returns mockk()
            every { downloadNovelChaptersPendingBroadcast(any(), any(), any(), any()) } returns mockk()
        }
    }

    abstract suspend fun showProgress()

    abstract suspend fun showNewChapters()

    abstract fun progressText(): CharSequence?

    abstract fun summaryText(): CharSequence?

    override fun close() {
        shade.close()
        unmockkStatic(MAIN_INTENT, NEW_CHAPTERS)
        Dispatchers.resetMain()
    }

    private companion object {
        const val MAIN_INTENT = "reikai.data.notification.MainActivityPendingIntentKt"
        const val NEW_CHAPTERS = "reikai.data.notification.NewChaptersSummaryKt"
    }
}

class MangaUpdateNoticeHalf : UpdateNoticeHalf() {

    private val manga = Manga.create().copy(id = 1L, title = "Source title")
    private var bigText: CharSequence? = null

    private val notifier = LibraryUpdateNotifier(
        context = context,
        securityPreferences = security,
        sourceManager = mockk(),
        adultCheckerProvider = { mockk { coEvery { adultIdsAmong(any()) } returns emptySet() } },
        getEntryCustomInfo = GetEntryCustomInfo(
            mockk { every { subscribeAll() } returns flowOf(listOf(CUSTOM)) },
            mockk { every { subscribeAll() } returns flowOf(emptyList()) },
        ),
    )

    init {
        mockkConstructor(NotificationCompat.BigTextStyle::class)
        every { anyConstructed<NotificationCompat.BigTextStyle>().bigText(any()) } answers {
            bigText = firstArg()
            self as NotificationCompat.BigTextStyle
        }
    }

    override suspend fun showProgress() = notifier.showProgressNotification(listOf(manga), 0, 1)

    override suspend fun showNewChapters() =
        notifier.showUpdateNotifications(listOf(manga to arrayOf(Chapter.create().copy(id = 1L, mangaId = 1L))))

    override fun progressText() = bigText

    override fun summaryText() = shade.textOf(Notifications.ID_NEW_CHAPTERS)

    override fun close() {
        unmockkConstructor(NotificationCompat.BigTextStyle::class)
        super.close()
    }

    private companion object {
        val CUSTOM = CustomMangaInfo(mangaId = 1L, title = "Mine")
    }
}

class NovelUpdateNoticeHalf : UpdateNoticeHalf() {

    private val novel = Novel.create().copy(id = 1L, source = "src", title = "Source title")

    private val notifier = NovelUpdateNotifier(
        context = context,
        securityPreferences = security,
        adultChecker = mockk { coEvery { adultNovelIdsAmong(any()) } returns emptySet() },
        getEntryCustomInfo = GetEntryCustomInfo(
            mockk { every { subscribeAll() } returns flowOf(emptyList()) },
            mockk { every { subscribeAll() } returns flowOf(listOf(CUSTOM)) },
        ),
    )

    override suspend fun showProgress() = notifier.showProgress(novel, 0, 1)

    override suspend fun showNewChapters() = notifier.showResults(listOf(novel to listOf(chapter)))

    override fun progressText() = shade.textOf(Notifications.ID_NOVEL_LIBRARY_PROGRESS)

    override fun summaryText() = shade.textOf(Notifications.ID_NOVEL_LIBRARY_RESULT)

    private companion object {
        val CUSTOM = CustomNovelInfo(novelId = 1L, title = "Mine")
        val chapter = NovelChapter(
            id = 10L,
            novelId = 1L,
            url = "u10",
            name = "Ch 10",
            read = false,
            bookmark = false,
            lastTextProgress = 0L,
            chapterNumber = 10.0,
            sourceOrder = 10L,
            dateFetch = 0L,
            dateUpload = 0L,
            page = "",
        )
    }
}
