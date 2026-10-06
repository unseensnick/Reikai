package reikai.presentation.settings

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.getCategoriesLabel
import eu.kanade.presentation.more.settings.widget.TriStateListDialog
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_CHARGING
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_NETWORK_NOT_METERED
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.core.common.preference.Preference as PreferenceData

/**
 * Mihon's interval and device-restriction rows, for every background checker. The widget stores a
 * choice only after `onValueChanged` returns, so the restriction row posts [setupTask] to the main
 * looper, where it runs after the write and schedules with the new set.
 */
@Composable
fun updateScheduleRows(
    interval: PreferenceData<Int>,
    restrictions: PreferenceData<Set<String>>,
    setupTask: (Context, Int?) -> Unit,
): List<Preference.PreferenceItem<out Any, out Any>> {
    val context = LocalContext.current
    val currentInterval by interval.collectAsState()
    return listOf(
        Preference.PreferenceItem.ListPreference(
            preference = interval,
            entries = mapOf(
                0 to stringResource(MR.strings.update_never),
                12 to stringResource(MR.strings.update_12hour),
                24 to stringResource(MR.strings.update_24hour),
                48 to stringResource(MR.strings.update_48hour),
                72 to stringResource(MR.strings.update_72hour),
                168 to stringResource(MR.strings.update_weekly),
            ),
            title = stringResource(MR.strings.pref_library_update_interval),
            onValueChanged = {
                setupTask(context, it)
                true
            },
        ),
        Preference.PreferenceItem.MultiSelectListPreference(
            preference = restrictions,
            entries = mapOf(
                DEVICE_ONLY_ON_WIFI to stringResource(MR.strings.connected_to_wifi),
                DEVICE_NETWORK_NOT_METERED to stringResource(MR.strings.network_not_metered),
                DEVICE_CHARGING to stringResource(MR.strings.charging),
            ),
            title = stringResource(MR.strings.pref_library_update_restriction),
            subtitle = stringResource(MR.strings.restrictions),
            visible = currentInterval > 0,
            onValueChanged = {
                ContextCompat.getMainExecutor(context).execute { setupTask(context, null) }
                true
            },
        ),
    )
}

/** Mihon's categories row, whose dialog includes and excludes [categories] by id. */
@Composable
fun categoryFilterPreference(
    categories: List<Category>,
    include: PreferenceData<Set<String>>,
    exclude: PreferenceData<Set<String>>,
    message: StringResource,
    visible: Boolean = true,
): Preference.PreferenceItem.TextPreference {
    val included by include.collectAsState()
    val excluded by exclude.collectAsState()
    var showDialog by rememberSaveable { mutableStateOf(false) }
    if (showDialog) {
        TriStateListDialog(
            title = stringResource(MR.strings.categories),
            message = stringResource(message),
            items = categories,
            initialChecked = included.mapNotNull { id -> categories.find { it.id.toString() == id } },
            initialInversed = excluded.mapNotNull { id -> categories.find { it.id.toString() == id } },
            itemLabel = { it.visualName },
            onDismissRequest = { showDialog = false },
            onValueChanged = { newIncluded, newExcluded ->
                include.set(newIncluded.map { it.id.toString() }.toSet())
                exclude.set(newExcluded.map { it.id.toString() }.toSet())
                showDialog = false
            },
        )
    }
    return Preference.PreferenceItem.TextPreference(
        title = stringResource(MR.strings.categories),
        subtitle = getCategoriesLabel(categories, included, excluded),
        visible = visible,
        onClick = { showDialog = true },
    )
}
