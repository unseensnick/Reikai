package reikai.util

import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * An ordered list of strings that never contain a newline (source ids, button codes), stored
 * newline-joined beside Mihon's comma-joined `getLongArray`. Unset reads as empty; blank lines drop.
 */
fun PreferenceStore.getStringList(key: String): Preference<List<String>> = getObjectFromString(
    key = key,
    defaultValue = emptyList(),
    serializer = { it.joinToString("\n") },
    deserializer = { it.split("\n").filter(String::isNotBlank) },
)
