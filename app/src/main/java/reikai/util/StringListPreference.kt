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
 * This list's own items in [keys]' order, then any item [keys] does not name, in its current order. A
 * key naming nothing here is skipped, so an order computed from an older copy of a list (a queue, or a
 * saved [getStringList] order) can be applied to the live one without bringing back what left it or
 * dropping what joined it.
 */
fun <T, K> List<T>.inOrderOf(keys: List<K>, keyOf: (T) -> K): List<T> {
    val byKey = associateBy(keyOf)
    val seen = HashSet<K>()
    return buildList {
        keys.forEach { key -> byKey[key]?.let { if (seen.add(key)) add(it) } }
        this@inOrderOf.forEach { if (seen.add(keyOf(it))) add(it) }
    }
}
