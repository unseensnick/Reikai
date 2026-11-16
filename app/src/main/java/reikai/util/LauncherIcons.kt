package reikai.util

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import mihon.core.metro.AppCoroutineScope
import tachiyomi.core.common.util.lang.launchIO

/**
 * An app icon the launcher can show: one `<activity-alias>` of [MainActivity] per icon in the manifest, each build
 * enabling its own current icon through a bool resource. Alias names are permanent, since a launcher holds a picked
 * icon by name (LauncherIconsTest).
 */
enum class LauncherIcon(
    val alias: String,
    @DrawableRes val foreground: Int,
    @DrawableRes val background: Int,
    val design: Design,
    val scheme: Scheme,
) {
    CURRENT_STABLE(
        "LauncherIconCurrentStable",
        R.drawable.ic_launcher_fg_current_stable,
        R.drawable.ic_launcher_bg_stable,
        Design.CURRENT,
        Scheme.STABLE,
    ),
    CURRENT_NIGHTLY(
        "LauncherIconCurrentNightly",
        R.drawable.ic_launcher_fg_current_nightly,
        R.drawable.ic_launcher_bg_nightly,
        Design.CURRENT,
        Scheme.NIGHTLY,
    ),
    CURRENT_DEV(
        "LauncherIconCurrentDev",
        R.drawable.ic_launcher_fg_current_dev,
        R.drawable.ic_launcher_bg_dev,
        Design.CURRENT,
        Scheme.DEV,
    ),
    ORIGINAL_RE_STABLE(
        "LauncherIconOriginalReStable",
        R.drawable.ic_launcher_fg_original_re_stable,
        R.drawable.ic_launcher_bg_stable,
        Design.ORIGINAL_RE,
        Scheme.STABLE,
    ),
    FLAME_R_STABLE(
        "LauncherIconFlameRStable",
        R.drawable.ic_launcher_fg_flame_r_stable,
        R.drawable.ic_launcher_bg_stable,
        Design.FLAME_R,
        Scheme.STABLE,
    ),
    ORIGINAL_RE_NIGHTLY(
        "LauncherIconOriginalReNightly",
        R.drawable.ic_launcher_fg_original_re_nightly,
        R.drawable.ic_launcher_bg_nightly,
        Design.ORIGINAL_RE,
        Scheme.NIGHTLY,
    ),
    FLAME_R_NIGHTLY(
        "LauncherIconFlameRNightly",
        R.drawable.ic_launcher_fg_flame_r_nightly,
        R.drawable.ic_launcher_bg_nightly,
        Design.FLAME_R,
        Scheme.NIGHTLY,
    ),
    ORIGINAL_RE_DEV(
        "LauncherIconOriginalReDev",
        R.drawable.ic_launcher_fg_original_re_dev,
        R.drawable.ic_launcher_bg_dev,
        Design.ORIGINAL_RE,
        Scheme.DEV,
    ),
    FLAME_R_DEV(
        "LauncherIconFlameRDev",
        R.drawable.ic_launcher_fg_flame_r_dev,
        R.drawable.ic_launcher_bg_dev,
        Design.FLAME_R,
        Scheme.DEV,
    ),
    FLAME_RE_STABLE(
        "LauncherIconFlameReStable",
        R.drawable.ic_launcher_fg_flame_re_stable,
        R.drawable.ic_launcher_bg_stable,
        Design.FLAME_RE,
        Scheme.STABLE,
    ),
    FLAME_RE_NIGHTLY(
        "LauncherIconFlameReNightly",
        R.drawable.ic_launcher_fg_flame_re_nightly,
        R.drawable.ic_launcher_bg_nightly,
        Design.FLAME_RE,
        Scheme.NIGHTLY,
    ),
    FLAME_RE_DEV(
        "LauncherIconFlameReDev",
        R.drawable.ic_launcher_fg_flame_re_dev,
        R.drawable.ic_launcher_bg_dev,
        Design.FLAME_RE,
        Scheme.DEV,
    ),
    MIHON_RE_STABLE(
        "LauncherIconMihonReStable",
        R.drawable.ic_launcher_fg_mihon_re_stable,
        R.drawable.ic_launcher_bg_mihon_stable,
        Design.MIHON_RE,
        Scheme.STABLE,
    ),
    MIHON_RE_NIGHTLY(
        "LauncherIconMihonReNightly",
        R.drawable.ic_launcher_fg_mihon_re_nightly,
        R.drawable.ic_launcher_bg_mihon_nightly,
        Design.MIHON_RE,
        Scheme.NIGHTLY,
    ),
    MIHON_RE_DEV(
        "LauncherIconMihonReDev",
        R.drawable.ic_launcher_fg_mihon_re_dev,
        R.drawable.ic_launcher_bg_mihon_dev,
        Design.MIHON_RE,
        Scheme.DEV,
    ),
    MIHON_RE_MIHON_LIGHT(
        "LauncherIconMihonReMihonLight",
        R.drawable.ic_launcher_fg_mihon_re_mihon_light,
        R.drawable.ic_launcher_bg_mihon_mihon_light,
        Design.MIHON_RE,
        Scheme.MIHON_LIGHT,
    ),
    MIHON_RE_MIHON_DARK(
        "LauncherIconMihonReMihonDark",
        R.drawable.ic_launcher_fg_mihon_re_mihon_dark,
        R.drawable.ic_launcher_bg_mihon_mihon_dark,
        Design.MIHON_RE,
        Scheme.MIHON_DARK,
    ),
    ;

    /** A design, with its line-art [logo]: the app's header and, inset, the launcher's themed icon. */
    enum class Design(@DrawableRes val logo: Int) {
        CURRENT(R.drawable.ic_reikai),
        ORIGINAL_RE(R.drawable.ic_logo_original_re),
        FLAME_R(R.drawable.ic_logo_flame_r),
        FLAME_RE(R.drawable.ic_logo_flame_re),
        MIHON_RE(R.drawable.ic_logo_mihon_re),
    }

    enum class Scheme { STABLE, NIGHTLY, DEV, MIHON_LIGHT, MIHON_DARK }

    // Class.getPackageName needs API 31, so the package comes off the class name.
    fun component(context: Context) = ComponentName(
        context.packageName,
        "${MainActivity::class.java.name.substringBeforeLast('.')}.$alias",
    )
}

/** The icon the launcher shows, for the picker, the app's header and notifications; null until the first read. */
@SingleIn(AppScope::class)
@Inject
class LauncherIconState(
    private val context: Context,
    @AppCoroutineScope private val scope: CoroutineScope,
) {
    val shown: StateFlow<LauncherIcon?> get() = Shown

    init {
        scope.launchIO { Shown.value = context.launcherIcon() }
    }

    // On the app scope: the old task closes partway through the switch, and the switch must still finish.
    fun pick(icon: LauncherIcon) {
        Shown.value = icon
        scope.launchIO {
            context.setLauncherIcon(icon)
            context.rebadgeNotifications(icon)
        }
    }

    companion object {
        // Process-wide so a notification reads it without the graph; only this class writes it.
        private val Shown = MutableStateFlow<LauncherIcon?>(null)

        internal val shownNow: LauncherIcon? get() = Shown.value
    }
}

/** A notification's small icon: the shown design's logo, or the original's until the launcher has been read. */
@Suppress("UnusedReceiverParameter")
val Context.notificationIcon: Int
    @DrawableRes get() = (LauncherIconState.shownNow?.design ?: LauncherIcon.Design.CURRENT).logo

/**
 * Re-posts the notifications up now that wear a design's logo with [icon]'s instead, since a notification keeps the
 * icon it was posted with. Only the icon changes, and none of them sounds again.
 */
internal fun Context.rebadgeNotifications(icon: LauncherIcon) {
    val logos = LauncherIcon.Design.entries.map { it.logo }.toSet()
    val manager = getSystemService(NotificationManager::class.java)
    // Notification.icon, not smallIcon: reading an Icon's resource needs API 28.
    @Suppress("DEPRECATION")
    manager.activeNotifications.filter { it.notification.icon in logos }.forEach {
        val rebadged = Notification.Builder.recoverBuilder(this, it.notification)
            .setSmallIcon(icon.design.logo)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(it.tag, it.id, rebadged)
    }
}

/** The icon the launcher shows now. Package manager calls are IPC, so call this off the main thread. */
fun Context.launcherIcon(): LauncherIcon? = LauncherIcon.entries.firstOrNull { isEnabled(it) }

/**
 * Shows [icon] in the launcher in place of the one there now. The new entry goes on before the old one goes off,
 * so the app is never left with none. Android closes a task started from an entry that goes off, so the app first
 * reopens from the new one rather than vanishing.
 */
fun Context.setLauncherIcon(icon: LauncherIcon) {
    val shown = LauncherIcon.entries.filter { isEnabled(it) }
    setEnabled(icon, true)
    // The open tasks close first: MainActivity is singleTop, so a start would otherwise land in the open screen and
    // close with its task. Android still allows the start in the moment after the app's own screen goes.
    getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
    startActivity(
        Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(icon.component(this))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
    shown.filter { it != icon }.forEach { setEnabled(it, false) }
}

// An entry never switched reports DEFAULT, which the manifest's own value (this build's bool resource) decides.
private fun Context.isEnabled(icon: LauncherIcon): Boolean =
    when (packageManager.getComponentEnabledSetting(icon.component(this))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ->
            packageManager.getActivityInfo(icon.component(this), PackageManager.MATCH_DISABLED_COMPONENTS).enabled
        else -> false
    }

private fun Context.setEnabled(icon: LauncherIcon, enabled: Boolean) {
    val state = if (enabled) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } else {
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }
    packageManager.setComponentEnabledSetting(icon.component(this), state, PackageManager.DONT_KILL_APP)
}
