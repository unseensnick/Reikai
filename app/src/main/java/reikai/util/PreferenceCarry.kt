package reikai.util

import tachiyomi.core.common.preference.Preference

/**
 * Hands a retired preference's stored value to [carry], then deletes the old key. One never set carries
 * nothing, so its successor keeps its own default instead of having the old default written into it.
 */
fun <T> Preference<T>.carryIfSet(carry: (T) -> Unit) {
    if (!isSet()) return
    carry(get())
    delete()
}
