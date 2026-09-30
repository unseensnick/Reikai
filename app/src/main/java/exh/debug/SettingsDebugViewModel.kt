package exh.debug

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.util.lang.launchIO
import java.util.Locale
import kotlin.reflect.KFunction
import kotlin.reflect.KVisibility
import kotlin.reflect.full.callSuspend
import kotlin.reflect.full.declaredFunctions

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SettingsDebugViewModel(
    private val functions: DebugFunctions,
    private val preferenceStore: PreferenceStore,
) : ViewModel() {

    data class Function(val label: String, val function: KFunction<*>)

    data class Toggle(val toggle: DebugToggles, val label: String, val enabled: Boolean) {
        val isModified: Boolean get() = enabled != toggle.default
    }

    /** A function's label and what it returned or threw, shown until dismissed. */
    data class Result(val label: String, val text: String)

    data class State(
        /** Null while the reflection that lists them runs, which is slow enough to keep off the main thread. */
        val functions: List<Function>? = null,
        val toggles: List<Toggle> = emptyList(),
        val running: Boolean = false,
        val result: Result? = null,
    )

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    init {
        viewModelScope.launchIO {
            state.update { it.copy(functions = menuFunctions()) }
        }
        viewModelScope.launchIO {
            combine(DebugToggles.entries.map { it.preference(preferenceStore).changes() }) { values ->
                DebugToggles.entries.mapIndexed { index, toggle ->
                    Toggle(toggle, toggle.name.toLabel('_'), values[index])
                }
            }.collectLatest { toggles -> state.update { it.copy(toggles = toggles) } }
        }
    }

    fun run(function: Function) {
        viewModelScope.launchIO {
            state.update { it.copy(running = true) }
            val text = try {
                "Function returned result:\n\n${function.function.callSuspend(functions)}"
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "Function threw exception:\n\n${Log.getStackTraceString(e)}"
            }
            state.update { it.copy(running = false, result = Result(function.label, text)) }
        }
    }

    fun toggle(toggle: Toggle) {
        toggle.toggle.preference(preferenceStore).set(!toggle.enabled)
    }

    fun dismissResult() {
        state.update { it.copy(result = null) }
    }

    companion object {
        /** Every public function of [DebugFunctions], labelled from its name ("countMangaInDatabase" reads "Count manga in database"). */
        internal fun menuFunctions(): List<Function> = DebugFunctions::class.declaredFunctions
            .filter { it.visibility == KVisibility.PUBLIC }
            .map { Function(it.name.replace(CAMEL_HUMP, "$1 $2").toLabel(' '), it) }
            .sortedBy { it.label }

        private val CAMEL_HUMP = "(.)(\\p{Upper})".toRegex()

        private fun String.toLabel(separator: Char): String = replace(separator, ' ')
            .lowercase(Locale.getDefault())
            .replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }
}
