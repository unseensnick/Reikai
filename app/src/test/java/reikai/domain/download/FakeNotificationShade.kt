package reikai.domain.download

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import eu.kanade.tachiyomi.data.notification.NotificationHandler
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.notificationManager
import eu.kanade.tachiyomi.util.system.notify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import tachiyomi.core.common.i18n.stringResource

/**
 * What both download notifiers leave in the notification shade, keyed by id, over Android's own
 * notification calls: Mihon's go through the context extensions, the novel notifier's through the
 * system manager. Each builder is a relaxed mock, so a test can read what was set on the one shown.
 */
class FakeNotificationShade : AutoCloseable {

    /** The builder behind each notification still shown, by id. */
    val shown = mutableMapOf<Int, NotificationCompat.Builder?>()

    private val builtBy = mutableMapOf<Notification, NotificationCompat.Builder>()

    init {
        mockkStatic(NOTIFICATION_EXTENSIONS)
        every { any<Context>().notificationBuilder(any(), any()) } answers {
            val notification = mockk<Notification>()
            val builder = mockk<NotificationCompat.Builder>(relaxed = true) { every { build() } returns notification }
            builtBy[notification] = builder
            thirdArg<(NotificationCompat.Builder.() -> Unit)?>()?.invoke(builder)
            builder
        }
        every { any<Context>().notify(any<Int>(), any<String>(), any()) } answers { callOriginal() }
        every { any<Context>().notify(any<Int>(), any<Notification>()) } answers {
            shown[secondArg()] = builtBy[thirdArg()]
        }
        every { any<Context>().cancelNotification(any()) } answers { shown.remove(secondArg<Int>()) }
        every { any<Context>().notificationManager } returns mockk<NotificationManager> {
            every { notify(any<Int>(), any()) } answers { shown[firstArg()] = builtBy[secondArg()] }
            every { cancel(any<Int>()) } answers { shown.remove(firstArg<Int>()) }
        }
        mockkStatic(LOCALIZE)
        every { any<Context>().stringResource(any()) } returns "text"
        every { any<Context>().stringResource(any(), *anyVararg()) } returns "text"
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeResource(any(), any()) } returns null
        mockkObject(NotificationHandler)
        every { NotificationHandler.openDownloadManagerPendingActivity(any()) } returns mockk()
        mockkObject(NotificationReceiver.Companion)
        with(NotificationReceiver.Companion) {
            every { pauseDownloadsPendingBroadcast(any()) } returns mockk()
            every { resumeDownloadsPendingBroadcast(any()) } returns mockk()
            every { clearDownloadsPendingBroadcast(any()) } returns mockk()
            every { openEntryPendingActivity(any(), any()) } returns mockk()
            every { pauseNovelDownloadsPendingBroadcast(any()) } returns mockk()
            every { resumeNovelDownloadsPendingBroadcast(any()) } returns mockk()
            every { cancelNovelDownloadPendingBroadcast(any()) } returns mockk()
            every { openNovelPendingActivity(any(), any()) } returns mockk()
        }
    }

    override fun close() {
        unmockkStatic(NOTIFICATION_EXTENSIONS, LOCALIZE)
        unmockkStatic(BitmapFactory::class)
        unmockkObject(NotificationHandler, NotificationReceiver.Companion)
    }

    private companion object {
        const val NOTIFICATION_EXTENSIONS = "eu.kanade.tachiyomi.util.system.NotificationExtensionsKt"
        const val LOCALIZE = "tachiyomi.core.common.i18n.LocalizeKt"
    }
}
