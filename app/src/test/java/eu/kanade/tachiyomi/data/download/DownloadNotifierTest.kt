package eu.kanade.tachiyomi.data.download

import android.app.Application
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.data.notification.Notifications
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.domain.download.FakeNotificationShade
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import tachiyomi.domain.manga.model.Manga

/** Each error notice is built fresh, so it carries nothing over from an earlier one (mihonapp/mihon#3341). */
class DownloadNotifierTest {

    private val shade = FakeNotificationShade()
    private val notifier =
        DownloadNotifier(mockk<Application>(relaxed = true), SecurityPreferences(InMemoryPreferenceStore())) {
            mockk { coEvery { adultIdsAmong(any()) } returns emptySet() }
        }

    @AfterEach
    fun tearDown() {
        shade.close()
    }

    @Test
    fun `an error after a warning shows none of the warning's expanded text`() = runTest {
        notifier.onWarning("warning")

        notifier.onError("error", "Ch 10", Manga.create().copy(id = 1L, title = "Manga"))

        val shown = shade.shown.getValue(Notifications.ID_DOWNLOAD_CHAPTER_ERROR)!!
        verify(exactly = 0) { shown.setStyle(any()) }
    }
}
