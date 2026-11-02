package eu.kanade.tachiyomi.data.notification

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.util.system.workManager
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import mihon.app.di.AppGraph
import mihon.core.metro.GraphProvider
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Refresh library covers posts the library notice, so that notice's Cancel has to reach it. */
@RunWith(RobolectricTestRunner::class)
class NotificationReceiverCancelTest {

    private val workManager = mockk<WorkManager>(relaxed = true)

    @Before
    fun setUp() {
        mockkStatic(WORK_MANAGER_EXTENSIONS)
        every { any<Context>().workManager } returns workManager
        mockkObject(LibraryUpdateWorker.Companion)
        coEvery { LibraryUpdateWorker.stop(any()) } returns Unit
    }

    @After
    fun tearDown() {
        unmockkObject(LibraryUpdateWorker.Companion)
        unmockkStatic(WORK_MANAGER_EXTENSIONS)
    }

    @Test
    fun `Cancel on the library notice stops a running cover refresh`() {
        val real = ApplicationProvider.getApplicationContext<Application>()
        val intent = shadowOf(NotificationReceiver.cancelLibraryUpdatePendingBroadcast(real)).savedIntent
        val app = mockk<Application>(relaxed = true, moreInterfaces = arrayOf(GraphProvider::class))
        every { app.applicationContext } returns app
        @Suppress("UNCHECKED_CAST")
        every { (app as GraphProvider<AppGraph>).graph } returns mockk(relaxed = true)
        val receiver = NotificationReceiver().apply {
            NotificationReceiver::class.java.getDeclaredField("scope").apply { isAccessible = true }
                .set(this, CoroutineScope(Dispatchers.Unconfined))
            pendingResultFor(this)
        }

        receiver.onReceive(app, intent)

        verify(timeout = 5_000) { workManager.cancelUniqueWork("MetadataUpdate") }
    }

    // Hidden in the SDK: goAsync hands back the result a real delivery sets.
    private fun pendingResultFor(receiver: BroadcastReceiver) {
        BroadcastReceiver::class.java
            .getDeclaredMethod("setPendingResult", BroadcastReceiver.PendingResult::class.java)
            .invoke(receiver, mockk<BroadcastReceiver.PendingResult>(relaxed = true))
    }

    private companion object {
        const val WORK_MANAGER_EXTENSIONS = "eu.kanade.tachiyomi.util.system.WorkManagerExtensionsKt"
    }
}
