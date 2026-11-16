package reikai.util

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import eu.kanade.tachiyomi.R
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The launcher entries behind the app icon switcher, against the merged manifest of the variant under test (debug).
 * A launcher holds a picked icon by its alias name, so a name that disappears in an update leaves that user with no
 * icon to open the app from: [ALIASES] may only grow.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherIconsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private fun launcherEntries() = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName),
        0,
    ).map { it.activityInfo.name.substringAfterLast('.') }

    @Test
    fun `the debug build starts with exactly its own icon in the launcher`() {
        launcherEntries() shouldBe listOf(LauncherIcon.CURRENT_DEV.alias)
    }

    @Test
    fun `the build's own icon reads as the one shown`() {
        context.launcherIcon() shouldBe LauncherIcon.CURRENT_DEV
    }

    @Test
    fun `a picked icon replaces the one shown, leaving exactly one`() {
        context.setLauncherIcon(LauncherIcon.MIHON_RE_MIHON_DARK)

        launcherEntries() shouldBe listOf(LauncherIcon.MIHON_RE_MIHON_DARK.alias)
    }

    @Test
    fun `a switch reopens the app from the picked icon`() {
        context.setLauncherIcon(LauncherIcon.FLAME_RE_NIGHTLY)

        shadowOf(context as Application).nextStartedActivity.component shouldBe
            LauncherIcon.FLAME_RE_NIGHTLY.component(context)
    }

    @Test
    fun `a switch re-badges a notification already up with the new logo`() {
        post(7, R.drawable.ic_reikai)

        context.rebadgeNotifications(LauncherIcon.FLAME_RE_DEV)

        iconOf(7) shouldBe R.drawable.ic_logo_flame_re
    }

    @Test
    fun `a switch leaves a notification with its own icon alone`() {
        post(8, android.R.drawable.stat_sys_download)

        context.rebadgeNotifications(LauncherIcon.FLAME_RE_DEV)

        iconOf(8) shouldBe android.R.drawable.stat_sys_download
    }

    private val notifications get() = context.getSystemService(NotificationManager::class.java)

    private fun post(id: Int, @DrawableRes icon: Int) {
        notifications.createNotificationChannel(NotificationChannel("test", "test", NotificationManager.IMPORTANCE_LOW))
        notifications.notify(id, NotificationCompat.Builder(context, "test").setSmallIcon(icon).build())
    }

    @Suppress("DEPRECATION")
    private fun iconOf(id: Int) = notifications.activeNotifications.single { it.id == id }.notification.icon

    @Test
    fun `every icon's alias is in the manifest`() {
        val missing = LauncherIcon.entries.filter {
            runCatching {
                context.packageManager.getActivityInfo(it.component(context), PackageManager.MATCH_DISABLED_COMPONENTS)
            }.isFailure
        }

        missing shouldBe emptyList()
    }

    @Test
    fun `no alias a launcher may hold has been dropped`() {
        ALIASES.filterNot { alias -> LauncherIcon.entries.any { it.alias == alias } } shouldBe emptyList()
    }

    private companion object {
        val ALIASES = listOf(
            "LauncherIconCurrentStable", "LauncherIconFlameRStable", "LauncherIconFlameReStable",
            "LauncherIconCurrentNightly", "LauncherIconFlameRNightly", "LauncherIconFlameReNightly",
            "LauncherIconCurrentDev", "LauncherIconFlameRDev", "LauncherIconFlameReDev",
            "LauncherIconMihonReStable", "LauncherIconMihonReNightly", "LauncherIconMihonReDev",
            "LauncherIconMihonReMihonLight", "LauncherIconMihonReMihonDark",
            "LauncherIconOriginalReStable", "LauncherIconOriginalReNightly", "LauncherIconOriginalReDev",
        )
    }
}
