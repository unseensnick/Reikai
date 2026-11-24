package reikai.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference

/** A switch over whether [element] is in this set, so a toggle and the set cannot disagree. */
fun <T> Preference<Set<T>>.containing(element: T): Preference<Boolean> = MembershipPreference(this, element)

// A data class, so a view rebuilt over the same preference compares equal and Compose keeps its collector.
private data class MembershipPreference<T>(
    private val base: Preference<Set<T>>,
    private val element: T,
) : Preference<Boolean> {

    override fun key() = base.key()

    override fun get() = element in base.get()

    override fun set(value: Boolean) {
        val current = base.get()
        base.set(if (value) current + element else current - element)
    }

    override fun isSet() = base.isSet()

    override fun delete() = set(false)

    override fun defaultValue() = element in base.defaultValue()

    override fun changes(): Flow<Boolean> = base.changes().map { element in it }

    override fun stateIn(scope: CoroutineScope): StateFlow<Boolean> =
        changes().stateIn(scope, SharingStarted.Eagerly, get())
}
