package reikai.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference

/**
 * Reads [this] no lower than [min], for a setting whose range was raised: a value saved under the old range,
 * or restored from a backup that has one, reads as the floor everywhere rather than at each reader.
 */
fun Preference<Int>.floored(min: Int): Preference<Int> = FlooredPreference(this, min)

// A data class, so a view rebuilt over the same preference compares equal and Compose keeps its collector.
private data class FlooredPreference(private val base: Preference<Int>, private val min: Int) : Preference<Int> {

    override fun key() = base.key()

    override fun get() = base.get().coerceAtLeast(min)

    override fun set(value: Int) = base.set(value)

    override fun isSet() = base.isSet()

    override fun delete() = base.delete()

    override fun defaultValue() = base.defaultValue().coerceAtLeast(min)

    override fun changes(): Flow<Int> = base.changes().map { it.coerceAtLeast(min) }

    override fun stateIn(scope: CoroutineScope): StateFlow<Int> =
        changes().stateIn(scope, SharingStarted.Eagerly, get())
}
