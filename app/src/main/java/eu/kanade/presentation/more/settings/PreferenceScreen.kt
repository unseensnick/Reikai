package eu.kanade.presentation.more.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastForEachIndexed
import eu.kanade.presentation.more.settings.screen.HighlightKey
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.presentation.more.settings.widget.PreferenceGroupHeader
import kotlinx.coroutines.delay
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import kotlin.time.Duration.Companion.seconds

/**
 * Preference Screen composable which contains a list of [Preference] items
 * @param items [Preference] items which should be displayed on the preference screen. An item can be a single [PreferenceItem] or a group ([Preference.PreferenceGroup])
 * @param modifier [Modifier] to be applied to the preferenceScreen layout
 */
@Composable
fun PreferenceScreen(
    items: List<Preference>,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    val state = rememberLazyListState()
    val highlightKey = SearchableSettings.highlightKey
    if (highlightKey != null) {
        LaunchedEffect(Unit) {
            val i = items.findHighlightedIndex(highlightKey)
            if (i >= 0) {
                delay(0.5.seconds)
                state.animateScrollToItem(i)
            }
            SearchableSettings.highlightKey = null
        }
    }

    ScrollbarLazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
    ) {
        items.fastForEachIndexed { i, preference ->
            when (preference) {
                // Create Preference Group
                is Preference.PreferenceGroup -> {
                    if (!preference.visible) return@fastForEachIndexed

                    item {
                        Column {
                            PreferenceGroupHeader(
                                title = preference.title,
                                // RK: a heading result carries HighlightKey.heading, not the bare title
                                highlighted = highlightKey != null &&
                                    HighlightKey.heading(preference.title).matches(highlightKey),
                            )
                        }
                    }
                    items(preference.preferenceItems) { item ->
                        PreferenceItem(
                            item = item,
                            highlightKey = highlightKey,
                            // RK: the group this item sits in, so highlighting is group-aware.
                            groupTitle = preference.title,
                        )
                    }
                    item {
                        if (i < items.lastIndex) {
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                // Create Preference Item
                is Preference.PreferenceItem<*, *> -> item {
                    PreferenceItem(
                        item = preference,
                        highlightKey = highlightKey,
                        groupTitle = null, // RK: top-level item, no group
                    )
                }
            }
        }
    }
}

// RK --> match on (group, title) so the scroll lands on the exact row when two rows share a title
internal fun List<Preference>.findHighlightedIndex(highlightKey: HighlightKey): Int {
    // A hidden group takes no rows in the list, so it must take none here.
    return filterNot { it is Preference.PreferenceGroup && !it.visible }.flatMap { pref ->
        if (pref is Preference.PreferenceGroup) {
            buildList<HighlightKey?> {
                add(HighlightKey.heading(pref.title)) // Header
                addAll(pref.preferenceItems.map { groupItem -> HighlightKey(pref.title, groupItem.title) })
                add(null) // Spacer
            }
        } else {
            listOf(HighlightKey(null, pref.title))
        }
    }.indexOfFirst { it != null && it.matches(highlightKey) }
}
// RK <--
