package reikai.presentation.recents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * A preference store whose [Preference.changes] actually emits. `InMemoryPreferenceStore`'s does not:
 * its flow yields nothing at all, so anything combining two preference flows never produces a value
 * and a test reads the seed instead of the derivation. Values are held as they are handed over, with
 * no serialization round trip, which is all an engine test needs.
 */
class EmittingPreferenceStore : PreferenceStore {

    private val flows = mutableMapOf<String, MutableStateFlow<Any?>>()
    private val written = mutableSetOf<String>()

    @Suppress("UNCHECKED_CAST")
    private fun <T> pref(key: String, defaultValue: T): Preference<T> =
        Emitting(
            key = key,
            defaultValue = defaultValue,
            flow = flows.getOrPut(key) { MutableStateFlow(defaultValue) },
            written = written,
        ) as Preference<T>

    override fun getString(key: String, defaultValue: String) = pref(key, defaultValue)

    override fun getLong(key: String, defaultValue: Long) = pref(key, defaultValue)

    override fun getInt(key: String, defaultValue: Int) = pref(key, defaultValue)

    override fun getFloat(key: String, defaultValue: Float) = pref(key, defaultValue)

    override fun getBoolean(key: String, defaultValue: Boolean) = pref(key, defaultValue)

    override fun getStringSet(key: String, defaultValue: Set<String>) = pref(key, defaultValue)

    override fun <T> getObjectFromString(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): Preference<T> = pref(key, defaultValue)

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = pref(key, defaultValue)

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): Preference<Set<T>> = pref(key, defaultValue)

    override fun getAll(): Map<String, *> = flows.mapValues { it.value.value }

    private class Emitting<T>(
        private val key: String,
        private val defaultValue: T,
        private val flow: MutableStateFlow<Any?>,
        private val written: MutableSet<String>,
    ) : Preference<T> {

        override fun key(): String = key

        @Suppress("UNCHECKED_CAST")
        override fun get(): T = flow.value as T

        override fun set(value: T) {
            written += key
            flow.value = value
        }

        override fun isSet(): Boolean = key in written

        override fun delete() {
            written -= key
            flow.value = defaultValue
        }

        override fun defaultValue(): T = defaultValue

        @Suppress("UNCHECKED_CAST")
        override fun changes(): Flow<T> = flow.asStateFlow() as Flow<T>

        @Suppress("UNCHECKED_CAST")
        override fun stateIn(scope: CoroutineScope): StateFlow<T> = flow.asStateFlow() as StateFlow<T>
    }
}
