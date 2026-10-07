package eu.kanade.tachiyomi.data.download

import android.app.Application
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.notification.Notifications
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.download.FakeNotificationShade
import reikai.domain.entry.EntryId
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.model.Manga

class DownloadNotifierTest {

    private val shade = FakeNotificationShade()
    private val manga = Manga.create().copy(id = 1L, title = "Manga")
    private val notifier = DownloadNotifier(
        mockk<Application>(relaxed = true),
        SecurityPreferences(InMemoryPreferenceStore()),
        mockk { coEvery { await(EntryId.Manga(1L)) } returns CustomMangaInfo(mangaId = 1L, title = "Mine") },
    ) {
        mockk { coEvery { adultIdsAmong(any()) } returns emptySet() }
    }

    @AfterEach
    fun tearDown() {
        shade.close()
    }

    /** Each error notice is built fresh, so it carries nothing over from an earlier one (mihonapp/mihon#3341). */
    @Test
    fun `an error after a warning shows none of the warning's expanded text`() = runTest {
        notifier.onWarning("warning")

        notifier.onError("error", "Ch 10", manga)

        val shown = shade.shown.getValue(Notifications.ID_DOWNLOAD_CHAPTER_ERROR)!!
        verify(exactly = 0) { shown.setStyle(any()) }
    }

    @Test
    fun `an error names the manga by its Edit info title`() = runTest {
        notifier.onError("error", "Ch 10", manga)

        shade.titleOf(Notifications.ID_DOWNLOAD_CHAPTER_ERROR) shouldBe "Mine: Ch 10"
    }

    /** A chapter name often repeats the source title, which is stripped before the Edit info title leads. */
    @Test
    fun `the progress notice names the manga by its Edit info title`() = runTest {
        val chapter = Chapter.create().copy(id = 10L, mangaId = 1L, name = "Manga - Ch 10")
        val download = Download(mockk(), manga, chapter).apply { pages = emptyList() }

        notifier.onProgressChange(download)

        shade.titleOf(Notifications.ID_DOWNLOAD_CHAPTER_PROGRESS) shouldBe "Mine - Ch 10"
    }
}
