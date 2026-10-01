package reikai.presentation.settings

import eu.kanade.presentation.more.settings.Preference
import tachiyomi.core.common.preference.Preference as PreferenceData

/**
 * Mihon's "Reset default user agent string" row, for an address its edit dialog cannot put back,
 * since `EditTextPreferenceWidget` refuses a blank value. [current] is the caller's collected value,
 * so the row recomposes after a reset. This DSL hides a disabled row, so it shows only while
 * [enabled] and the value is off its default.
 */
fun resetToDefaultPreference(
    preference: PreferenceData<String>,
    current: String,
    title: String,
    enabled: Boolean = true,
): Preference.PreferenceItem.TextPreference = Preference.PreferenceItem.TextPreference(
    title = title,
    enabled = enabled && current != preference.defaultValue(),
    onClick = preference::delete,
)
