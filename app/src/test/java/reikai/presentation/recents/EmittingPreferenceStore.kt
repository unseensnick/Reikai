package reikai.presentation.recents

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/**
 * A preference store whose [Preference.changes] actually emits. `InMemoryPreferenceStore`'s does not:
 * its flow yields nothing at all, so anything combining two preference flows never produces a value
 * and a test reads the seed instead of the derivation. Values are held as they are handed over, with
 * no serialization round trip, which is all an engine test needs; only [getAll] serializes, below, and an
 * object stored as an Int is held as that Int, as a restore writes it.
 */
class EmittingPreferenceStore : PreferenceStore {

    private val flows = mutableMapOf<String, MutableStateFlow<Any?>>()
    private val written = mutableSetOf<String>()
    private val serializers = mutableMapOf<String, (Any?) -> String>()

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
    ): Preference<T> {
        @Suppress("UNCHECKED_CAST")
        serializers[key] = { serializer(it as T) }
        return pref(key, defaultValue)
    }

    override fun <T> getObjectFromInt(
        key: String,
        defaultValue: T,
        serializer: (T) -> Int,
        deserializer: (Int) -> T,
    ): Preference<T> = FromInt(pref(key, serializer(defaultValue)), defaultValue, serializer, deserializer)

    override fun <T> getObjectSetFromStringSet(
        key: String,
        defaultValue: Set<T>,
        serializer: (T) -> String,
        deserializer: (String) -> T?,
    ): Preference<Set<T>> = pref(key, defaultValue)

    // SharedPreferences reports an object preference as the string it stores, and a backup restore
    // checks that type before writing, so reporting the object would turn the restore away.
    override fun getAll(): Map<String, *> =
        flows.mapValues { (key, flow) -> serializers[key]?.invoke(flow.value) ?: flow.value }

    // Held as the Int SharedPreferences stores, so a restore's getInt write reads back as the object.
    private class FromInt<T>(
        private val stored: Preference<Int>,
        private val defaultValue: T,
        private val serializer: (T) -> Int,
        private val deserializer: (Int) -> T,
    ) : Preference<T> {
        override fun key(): String = stored.key()

        override fun get(): T = deserializer(stored.get())

        override fun set(value: T) = stored.set(serializer(value))

        override fun isSet(): Boolean = stored.isSet()

        override fun delete() = stored.delete()

        override fun defaultValue(): T = defaultValue

        override fun changes(): Flow<T> = stored.changes().map(deserializer)

        override fun stateIn(scope: CoroutineScope): StateFlow<T> =
            changes().stateIn(scope, SharingStarted.Eagerly, get())
    }

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
