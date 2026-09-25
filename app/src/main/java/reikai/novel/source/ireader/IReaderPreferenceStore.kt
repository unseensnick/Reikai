package reikai.novel.source.ireader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import reikai.novel.source.ireaderStorageScope
import tachiyomi.core.common.preference.PreferenceStore
import ireader.core.prefs.Preference as IReaderPreference
import ireader.core.prefs.PreferenceStore as IReaderStore

/**
 * One IReader extension's settings, kept in Reikai's store under its package name, as LN plugins keep
 * theirs under their id, so two extensions never read each other's keys.
 */
class IReaderPreferenceStore(
    private val store: PreferenceStore,
    packageName: String,
) : IReaderStore {

    private val prefix = ireaderStorageScope(packageName)

    override fun getString(key: String, defaultValue: String): IReaderPreference<String> =
        store.getString(prefix + key, defaultValue).bridged(key)

    override fun getLong(key: String, defaultValue: Long): IReaderPreference<Long> =
        store.getLong(prefix + key, defaultValue).bridged(key)

    override fun getInt(key: String, defaultValue: Int): IReaderPreference<Int> =
        store.getInt(prefix + key, defaultValue).bridged(key)

    override fun getFloat(key: String, defaultValue: Float): IReaderPreference<Float> =
        store.getFloat(prefix + key, defaultValue).bridged(key)

    override fun getBoolean(key: String, defaultValue: Boolean): IReaderPreference<Boolean> =
        store.getBoolean(prefix + key, defaultValue).bridged(key)

    override fun getStringSet(key: String, defaultValue: Set<String>): IReaderPreference<Set<String>> =
        store.getStringSet(prefix + key, defaultValue).bridged(key)

    override fun <T> getObject(
        key: String,
        defaultValue: T,
        serializer: (T) -> String,
        deserializer: (String) -> T,
    ): IReaderPreference<T> = store.getObjectFromString(
        prefix + key,
        defaultValue,
        serializer,
        deserializer,
    ).bridged(key)

    override fun <T> getJsonObject(
        key: String,
        defaultValue: T,
        serializer: KSerializer<T>,
        serializersModule: SerializersModule,
    ): IReaderPreference<T> {
        val json = Json { this.serializersModule = serializersModule }
        val encode: (T) -> String = { json.encodeToString(serializer, it) }
        val decode: (String) -> T = { json.decodeFromString(serializer, it) }
        return getObject(key, defaultValue, encode, decode)
    }
}

// The extension sees the key it asked for, not the namespaced one it is stored under.
private fun <T> tachiyomi.core.common.preference.Preference<T>.bridged(key: String): IReaderPreference<T> {
    val pref = this
    return object : IReaderPreference<T> {
        override fun key(): String = key
        override fun get(): T = pref.get()
        override fun set(value: T) = pref.set(value)
        override fun isSet(): Boolean = pref.isSet()
        override fun delete() = pref.delete()
        override fun defaultValue(): T = pref.defaultValue()
        override fun changes(): Flow<T> = pref.changes()
        override fun stateIn(scope: CoroutineScope): StateFlow<T> = pref.stateIn(scope)
    }
}
