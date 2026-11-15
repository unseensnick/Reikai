package reikai.ui.icon

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The launcher entries the app icon switcher toggles, read from the merged manifest of the variant under test
 * (debug). A launcher holds a picked icon by its alias name, so a name that disappears in an update leaves that
 * user with no icon to open the app from: the list below may only grow.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherIconAliasesTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `the debug build starts with exactly its own icon in the launcher`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)

        context.packageManager.queryIntentActivities(launcher, 0).map { it.activityInfo.name } shouldBe
            listOf("eu.kanade.tachiyomi.ui.main.LauncherIconCurrentDev")
    }

    @Test
    fun `every alias a launcher may hold still exists`() {
        val missing = ALIASES.filter {
            runCatching {
                context.packageManager.getActivityInfo(
                    ComponentName(context.packageName, "eu.kanade.tachiyomi.ui.main.$it"),
                    PackageManager.MATCH_DISABLED_COMPONENTS,
                )
            }.isFailure
        }

        missing shouldBe emptyList()
    }

    private companion object {
        val ALIASES = listOf(
            "LauncherIconCurrentStable", "LauncherIconFlameRStable", "LauncherIconFlameReStable",
            "LauncherIconCurrentNightly", "LauncherIconFlameRNightly", "LauncherIconFlameReNightly",
            "LauncherIconCurrentDev", "LauncherIconFlameRDev", "LauncherIconFlameReDev",
            "LauncherIconMihonReStable", "LauncherIconMihonReNightly", "LauncherIconMihonReDev",
            "LauncherIconMihonReMihonLight", "LauncherIconMihonReMihonDark",
        )
    }
}
