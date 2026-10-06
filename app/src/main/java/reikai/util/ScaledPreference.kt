package reikai.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference
import kotlin.math.roundToInt

/**
 * An integer view of a float stored as itself, counted in [by]ths of it, for the sliders and steppers that
 * only move in whole steps. Reads round, because 0.53f times a hundred is 52.999996 and truncating shows 52.
 */
fun Preference<Float>.scaled(by: Int): Preference<Int> = ScaledPreference(this, by)

// A data class, so a view rebuilt over the same preference compares equal and Compose keeps its collector.
private data class ScaledPreference(private val base: Preference<Float>, private val by: Int) : Preference<Int> {

    override fun key() = base.key()

    override fun get() = toScaled(base.get())

    override fun set(value: Int) = base.set(value.toFloat() / by)

    override fun isSet() = base.isSet()

    override fun delete() = base.delete()

    override fun defaultValue() = toScaled(base.defaultValue())

    override fun changes(): Flow<Int> = base.changes().map(::toScaled)

    override fun stateIn(scope: CoroutineScope): StateFlow<Int> =
        changes().stateIn(scope, SharingStarted.Eagerly, get())

    private fun toScaled(value: Float) = (value * by).roundToInt()
}
