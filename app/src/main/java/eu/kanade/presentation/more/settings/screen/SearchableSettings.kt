package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.navigation3.runtime.NavKey
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import mihon.core.navigation.LocalBackButtonVisibility
import mihon.core.navigation.LocalNavigator

interface SearchableSettings : NavKey {

    @Composable
    @ReadOnlyComposable
    fun getTitleRes(): StringResource

    // RK: lets a screen hide itself from the search index when its feature is off

    /** Whether this screen is currently reachable; a hidden screen is left out of the settings search
     *  index. Suspends because a gate may await the extension scan. Defaults to always-on. */
    suspend fun isEnabled(): Boolean = true

    @Composable
    fun getPreferences(): List<Preference>

    @Composable
    fun RowScope.AppBarAction() {
    }

    @Composable
    fun Content() {
        val navigator = LocalNavigator.current
        val backVisibility = LocalBackButtonVisibility.current

        PreferenceScaffold(
            titleRes = getTitleRes(),
            onBackPressed = {
                navigator.pop()
                Unit
            }.takeIf { backVisibility },
            actions = { AppBarAction() },
            itemsProvider = { getPreferences() },
        )
    }

    companion object {
        // HACK: for the background blipping thingy.
        // RK --> the key moved from a title to HighlightKey, so the notes below were reworded
        // The target PreferenceItem to scroll to + highlight on the destination screen.
        // Set before showing the destination screen and reset after.
        // See BasePreferenceWidget.highlightBackground
        // RK: (group, title) instead of title alone, so it lands on the exact row when two rows share a
        // title in different groups (the content-type "· Manga" / "· Novels" sub-groups). A null group
        // matches by title only (used by the onboarding restore-setting jump).
        var highlightKey: HighlightKey? = null
        // RK <--
    }
}

// RK -->
data class HighlightKey(val group: String?, val title: String) {
    /** True when this item position matches the search [key]. A null [key] group matches by title only. */
    fun matches(key: HighlightKey): Boolean = title == key.title && (key.group == null || group == key.group)

    companion object {
        /** A group's own heading, keyed under itself so a same-titled row in another group cannot match it. */
        fun heading(title: String) = HighlightKey(group = title, title = title)
    }
}
// RK <--
