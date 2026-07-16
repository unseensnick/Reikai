package eu.kanade.presentation.more.settings.screen

import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import cafe.adriel.voyager.core.screen.Screen
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.PreferenceScaffold
import eu.kanade.presentation.util.LocalBackPress

interface SearchableSettings : Screen {

    @Composable
    @ReadOnlyComposable
    fun getTitleRes(): StringResource

    /** Whether this screen is currently reachable; a hidden screen is filtered out of the main list
     *  and the settings search index. Defaults to always-on. */
    fun isEnabled(): Boolean = true

    @Composable
    fun getPreferences(): List<Preference>

    @Composable
    fun RowScope.AppBarAction() {
    }

    @Composable
    override fun Content() {
        val handleBack = LocalBackPress.current
        PreferenceScaffold(
            titleRes = getTitleRes(),
            onBackPressed = if (handleBack != null) handleBack::invoke else null,
            actions = { AppBarAction() },
            itemsProvider = { getPreferences() },
        )
    }

    companion object {
        // HACK: for the background blipping thingy.
        // The target PreferenceItem to scroll to + highlight on the destination screen.
        // Set before showing the destination screen and reset after.
        // See BasePreferenceWidget.highlightBackground
        // RK: (group, title) instead of title alone, so it lands on the exact row when two rows share a
        // title in different groups (the content-type "· Manga" / "· Novels" sub-groups). A null group
        // matches by title only (used by the onboarding restore-setting jump).
        var highlightKey: HighlightKey? = null
    }
}

// RK -->
data class HighlightKey(val group: String?, val title: String) {
    /** True when this item position matches the search [key]. A null [key] group matches by title only. */
    fun matches(key: HighlightKey): Boolean = title == key.title && (key.group == null || group == key.group)
}
// RK <--
