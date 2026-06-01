package yokai.presentation.library.settings.tabs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.ui.library.LibraryGroup
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.base.BasePreferences
import yokai.domain.novel.NovelPreferences

/**
 * Group-by tab: pick how the library is sectioned (by categories, tag, source, status, tracking
 * status, author, language, or ungrouped). Writes the same `groupLibraryBy()` pref the standalone
 * [yokai.presentation.library.components.GroupLibraryByPicker] uses, routed per the shared display
 * toggle (shared mode mirrors the manga grouping). The Novels tab omits "By language" since novel
 * dynamic grouping has no language field; if the shared manga value is "By language" the novel
 * library coerces it back to default grouping.
 */
@Composable
fun GroupTab(isNovelTab: Boolean = false) {
    val preferences: PreferencesHelper = remember { Injekt.get() }
    val novelPrefs: NovelPreferences = remember { Injekt.get() }
    val basePrefs: BasePreferences = remember { Injekt.get() }
    val useSharedLibraryDisplayPrefs by basePrefs.useSharedLibraryDisplayPrefs().collectAsState()
    val routeShared = isNovelTab && !useSharedLibraryDisplayPrefs
    val groupPref = rememberRoutedPref(routeShared, preferences.groupLibraryBy(), novelPrefs.groupLibraryBy())
    val selected by groupPref.collectAsState()

    val options = buildList {
        add(LibraryGroup.BY_DEFAULT)
        add(LibraryGroup.BY_TAG)
        add(LibraryGroup.BY_SOURCE)
        add(LibraryGroup.BY_STATUS)
        add(LibraryGroup.BY_TRACK_STATUS)
        add(LibraryGroup.BY_AUTHOR)
        if (!isNovelTab) add(LibraryGroup.BY_LANGUAGE)
        add(LibraryGroup.UNGROUPED)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(vertical = 8.dp),
    ) {
        options.forEach { group ->
            GroupRow(
                label = stringResource(LibraryGroup.groupTypeStringRes(group)),
                iconRes = LibraryGroup.groupTypeDrawableRes(group),
                selected = selected == group,
                onClick = { groupPref.set(group) },
            )
        }
    }
}

@Composable
private fun GroupRow(
    label: String,
    iconRes: Int,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp),
        )
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}
