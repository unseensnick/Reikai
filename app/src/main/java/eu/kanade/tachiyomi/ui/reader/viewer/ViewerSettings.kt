package eu.kanade.tachiyomi.ui.reader.viewer

import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView.ZoomStartPosition
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.DisabledNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.EdgeNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.KindlishNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.LNavigation
import eu.kanade.tachiyomi.ui.reader.viewer.navigation.RightAndLeftNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import reikai.presentation.reader.navigation.MangaTapLayouts

/**
 * A viewer's tap zones, as the preferences store them.
 */
data class NavigationSettings(
    // RK --> the stored index rather than upstream's NavigationMode, which has no entry for the layouts the novel
    // reader brought and would turn them into Default. The Bottom layout's height is read for every viewer.
    val mode: Int,
    val inverted: ReaderPreferences.TappingInvertMode,
    val bottomZoneHeight: Int,
    // RK <--
) {

    fun navigator(
        isReversed: Boolean,
        default: (ReaderPreferences.TappingInvertMode, Boolean) -> ViewerNavigation,
    ): ViewerNavigation {
        return MangaTapLayouts.navigation(mode, bottomZoneHeight, inverted, isReversed) // RK
            ?: when (mode) {
                1 -> LNavigation(inverted, isReversed)
                2 -> KindlishNavigation(inverted, isReversed)
                3 -> EdgeNavigation(inverted, isReversed)
                4 -> RightAndLeftNavigation(inverted, isReversed)
                MangaTapLayouts.DISABLED -> DisabledNavigation(inverted, isReversed) // RK
                else -> default(inverted, isReversed)
            }
    }
}

/**
 * Where a page starts zoomed for this setting, where [auto] is what automatic means for the viewer.
 */
fun ReaderPreferences.ZoomStartType.position(auto: ZoomStartPosition): ZoomStartPosition {
    return when (this) {
        ReaderPreferences.ZoomStartType.AUTOMATIC -> auto
        ReaderPreferences.ZoomStartType.LEFT -> ZoomStartPosition.LEFT
        ReaderPreferences.ZoomStartType.RIGHT -> ZoomStartPosition.RIGHT
        ReaderPreferences.ZoomStartType.CENTER -> ZoomStartPosition.CENTER
    }
}

/**
 * Runs [action] in [scope] with each value and the one before it, null for the first, so a viewer applies a change
 * in one place by comparing the parts it reacts to.
 */
fun <T : Any> Flow<T>.collectChanges(scope: CoroutineScope, action: (old: T?, new: T) -> Unit) {
    scope.launch {
        var old: T? = null
        collect {
            action(old, it)
            old = it
        }
    }
}
