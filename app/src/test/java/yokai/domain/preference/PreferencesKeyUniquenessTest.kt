package yokai.domain.preference

import android.content.Context
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.core.security.SecurityPreferences
import eu.kanade.tachiyomi.core.storage.FolderProvider
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.network.NetworkPreferences
import io.mockk.mockk
import java.io.File
import kotlin.reflect.KClass
import kotlin.reflect.full.declaredMemberFunctions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import yokai.domain.backup.BackupPreferences
import yokai.domain.base.BasePreferences
import yokai.domain.library.LibraryPreferences
import yokai.domain.novel.NovelPreferences
import yokai.domain.recents.RecentsPreferences
import yokai.domain.source.SourcePreferences
import yokai.domain.storage.StoragePreferences
import yokai.domain.ui.UiPreferences
import yokai.domain.ui.settings.ReaderPreferences

/**
 * Guards against the appIcon-style bug where two preference accessors silently
 * share the same SharedPreferences key, causing `ClassCastException` at the
 * first untyped read (e.g. when the search bar enumerates every settings
 * controller at once).
 *
 * If this test fails, two `preferenceStore.get*()` calls in this fork's
 * preference classes resolve to the same key. Find the duplicate keys printed
 * in the failure message and give one of them its own slot.
 */
class PreferencesKeyUniquenessTest {

    @Test
    fun `no two preference accessors share a key`() {
        val recorder = RecordingPreferenceStore()
        val folderProvider = object : FolderProvider {
            override fun directory(): File = File(".")
            override fun path(): String = ""
        }

        val accessorInstances: List<Any> = listOf(
            BasePreferences(recorder),
            SecurityPreferences(recorder),
            ReaderPreferences(recorder),
            SourcePreferences(recorder),
            RecentsPreferences(recorder),
            LibraryPreferences(recorder),
            NovelPreferences(recorder),
            BackupPreferences(recorder),
            UiPreferences(recorder),
            NetworkPreferences(recorder, verboseLogging = false),
            StoragePreferences(folderProvider, recorder),
            PreferencesHelper(mockk<Context>(relaxed = true), recorder),
        )

        accessorInstances.forEach { instance ->
            val className = instance::class.simpleName ?: "?"
            instance::class.declaredMemberFunctions
                .filter { it.parameters.size == 1 } // receiver only — skip pass-through methods that take a key arg
                .forEach { fn ->
                    recorder.beginCall("$className.${fn.name}")
                    try {
                        fn.call(instance)
                    } catch (_: Throwable) {
                        // A few accessors transitively touch infra (network, Android, etc.) that's not
                        // available in unit-test scope. Their keys may still have been recorded before
                        // the throw — skip the rest of the body and move on.
                    }
                }
        }

        val collisions = recorder.collisions()

        assertTrue(collisions.isEmpty()) {
            buildString {
                appendLine("Preference keys must be unique across all *Preferences classes.")
                appendLine("Two accessors writing the same key cause a ClassCastException on the first untyped read.")
                appendLine("Collisions:")
                collisions.forEach { (key, callers) ->
                    appendLine("  \"$key\" used by: ${callers.joinToString(", ")}")
                }
            }
        }
    }

    private class RecordingPreferenceStore : PreferenceStore {
        private var currentCaller: String = "?"
        private val callersByKey: MutableMap<String, MutableSet<String>> = mutableMapOf()

        fun beginCall(caller: String) {
            currentCaller = caller
        }

        fun collisions(): Map<String, Set<String>> =
            callersByKey.filterValues { it.size > 1 }

        private fun record(key: String) {
            callersByKey.getOrPut(key) { mutableSetOf() }.add(currentCaller)
        }

        override fun getString(key: String, defaultValue: String): Preference<String> {
            record(key); return FakePreference(key, defaultValue)
        }
        override fun getLong(key: String, defaultValue: Long): Preference<Long> {
            record(key); return FakePreference(key, defaultValue)
        }
        override fun getInt(key: String, defaultValue: Int): Preference<Int> {
            record(key); return FakePreference(key, defaultValue)
        }
        override fun getFloat(key: String, defaultValue: Float): Preference<Float> {
            record(key); return FakePreference(key, defaultValue)
        }
        override fun getBoolean(key: String, defaultValue: Boolean): Preference<Boolean> {
            record(key); return FakePreference(key, defaultValue)
        }
        override fun getStringSet(key: String, defaultValue: Set<String>): Preference<Set<String>> {
            record(key); return FakePreference(key, defaultValue)
        }
        override fun <T> getObject(
            key: String,
            defaultValue: T,
            serializer: (T) -> String,
            deserializer: (String) -> T,
        ): Preference<T> {
            record(key); return FakePreference(key, defaultValue)
        }
        override fun getAll(): Map<String, *> = emptyMap<String, Any>()
    }

    private class FakePreference<T>(private val key: String, default: T) : Preference<T> {
        private val state = MutableStateFlow(default)
        override fun key(): String = key
        override fun get(): T = state.value
        override fun set(value: T) { state.value = value }
        override fun isSet(): Boolean = true
        override fun delete() { /* no-op */ }
        override fun defaultValue(): T = state.value
        override fun changes(): Flow<T> = state.asStateFlow()
        override fun stateIn(scope: CoroutineScope): StateFlow<T> = state.asStateFlow()
    }
}
