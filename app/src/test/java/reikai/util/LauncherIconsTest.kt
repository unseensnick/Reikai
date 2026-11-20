package reikai.util

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
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
import java.io.File
import kotlin.math.abs

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

    private fun setState(icon: LauncherIcon, state: Int) = context.packageManager.setComponentEnabledSetting(
        ComponentName(context.packageName, "eu.kanade.tachiyomi.ui.main.${icon.alias}"),
        state,
        PackageManager.DONT_KILL_APP,
    )

    private fun shownAfterTheUpdate() = launcherEntries() to context.launcherIcon()

    // A launcher drops every pinned shortcut of an entry that stops being one, and every debug and nightly build
    // since the aliases launched from its own alias, so a never-picked install must keep that one on.
    @Test
    fun `an update with no icon picked keeps the debug build's own alias as its only entry`() {
        shownAfterTheUpdate() shouldBe (listOf(LauncherIcon.CURRENT_DEV.alias) to LauncherIcon.CURRENT_DEV)
    }

    @Test
    fun `picking the build's own icon again brings back its own alias`() {
        context.setLauncherIcon(LauncherIcon.FLAME_R_DEV)
        context.setLauncherIcon(LauncherIcon.CURRENT_DEV)

        launcherEntries() shouldBe listOf(LauncherIcon.CURRENT_DEV.alias)
    }

    @Test
    fun `the main screen opens by class with another icon picked`() {
        context.setLauncherIcon(LauncherIcon.FLAME_R_DEV)

        val start = Intent(context, MainActivityTarget::class.java)

        context.packageManager.resolveActivity(start, 0)?.activityInfo?.name shouldBe
            MainActivityTarget::class.java.name
    }

    // What a pick on a build since the aliases left behind: its alias on, the own alias off. Read with nothing run
    // first, as the startup read is.
    @Test
    fun `an icon picked before the update stays the only one and reads as shown`() {
        setState(LauncherIcon.MIHON_RE_DEV, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        setState(LauncherIcon.CURRENT_DEV, PackageManager.COMPONENT_ENABLED_STATE_DISABLED)

        shownAfterTheUpdate() shouldBe (listOf(LauncherIcon.MIHON_RE_DEV.alias) to LauncherIcon.MIHON_RE_DEV)
    }

    @Test
    fun `the build's own icon picked before the update stays the only one and reads as shown`() {
        setState(LauncherIcon.CURRENT_DEV, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)

        shownAfterTheUpdate() shouldBe (listOf(LauncherIcon.CURRENT_DEV.alias) to LauncherIcon.CURRENT_DEV)
    }

    // Robolectric merges only the debug manifest, so the release and nightly entries are pinned by their resources.
    @Test
    fun `only a release build shows its own icon on the entry every release launched from`() {
        val bools = listOf("main", "nightly", "debug").associateWith { variant ->
            Regex("""<bool name="(launcher_[a-z_]+)">(true|false)</bool>""")
                .findAll(File("src/$variant/res/values/launcher_icons.xml").readText())
                .associate { it.groupValues[1] to it.groupValues[2].toBoolean() }
        }
        val onByDefault = bools.mapValues { (_, own) -> (bools.getValue("main") + own).filterValues { it }.keys }

        onByDefault shouldBe mapOf(
            "main" to setOf("launcher_own_on_legacy_entry"),
            "nightly" to setOf("launcher_icon_current_nightly"),
            "debug" to setOf("launcher_icon_current_dev"),
        )
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
    fun `the picker draws an Outline logo at the inset its themed layer uses`() {
        val insets = File("src/main/res/drawable").listFiles { f -> f.name.startsWith("ic_launcher_mono") }!!
            .map { Regex("""android:inset="([0-9.]+)%"""").find(it.readText())!!.groupValues[1].toFloat() / 100 }

        // The XML writes the inset to four decimals of a percent, so equal means within that.
        insets.map { abs(it - LauncherIcon.THEMED_INSET) < 1e-6f }.distinct() shouldBe listOf(true)
    }

    @Test
    fun `every icon's alias is in the manifest`() {
        val missing = LauncherIcon.entries.filter {
            runCatching {
                context.packageManager.getActivityInfo(
                    ComponentName(context.packageName, "eu.kanade.tachiyomi.ui.main.${it.alias}"),
                    PackageManager.MATCH_DISABLED_COMPONENTS,
                )
            }.isFailure
        }

        missing shouldBe emptyList()
    }

    // MainActivity's name is a launcher entry, off while another icon is picked, and starting an entry that is off
    // fails. So notifications, widgets and shortcuts name the class behind every entry; the manifest declares it.
    @Test
    fun `nothing starts the main screen by the name of a launcher entry`() {
        val roots = listOf("src/main", "../presentation-widget/src/main", "../core/common/src/main").map(::File)
        val files = roots.flatMap { root -> root.walk().filter { it.extension in setOf("kt", "xml") } }
        val starts = files.filter { it.name != "AndroidManifest.xml" }.filter {
            it.readText().let { text -> "MainActivity::class.java" in text || "ui.main.MainActivity\"" in text }
        }

        starts.map { it.name } shouldBe emptyList()
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
            "LauncherIconCurrentOutline", "LauncherIconOriginalReOutline", "LauncherIconFlameROutline",
            "LauncherIconFlameReOutline", "LauncherIconMihonReOutline",
        )
    }
}
