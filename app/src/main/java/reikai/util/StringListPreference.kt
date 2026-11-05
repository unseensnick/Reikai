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

/**
 * This list in a saved [order] of keys: the items it names first, as saved, then the rest in their own
 * order, so an item added after the order was saved goes last. A key that names nothing is ignored.
 */
fun <T> List<T>.inSavedOrder(order: List<String>, keyOf: (T) -> String): List<T> {
    val byKey = associateBy(keyOf)
    val placed = order.distinct().mapNotNull(byKey::get)
    return placed + (this - placed.toSet())
}
