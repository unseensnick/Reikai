package reikai.presentation.novel.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import reikai.novel.source.NovelSettings
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext

/**
 * The values [NovelSourceSettingsSheet] edits, read from and written to a plugin's storage off the
 * main thread. The model lives as long as the screen hosting the sheet, so the sheet loads on every
 * open and clears on close, and an edit dismissed unsaved is gone.
 */
class NovelSourceSettingsModel : ViewModel() {

    /** Each setting's stored value, else its declared default; null while loading or closed. */
    val draft: StateFlow<Map<String, JsonElement>?>
        field = MutableStateFlow<Map<String, JsonElement>?>(null)

    // The draft as loaded, so a save writes only what the user changed: a default written to storage
    // would outlive a plugin update that changes it, as LNReader writes only the changed field.
    @Volatile
    private var loaded: Map<String, JsonElement> = emptyMap()

    fun load(settings: NovelSettings.LnSchema) {
        clear()
        viewModelScope.launchIO {
            val values = settings.schema.mapNotNull { (key, schema) ->
                val declared = schema as? JsonObject ?: return@mapNotNull null
                (settings.get(key) ?: declared["value"])?.let { key to it }
            }.toMap()
            loaded = values
            draft.value = values
        }
    }

    fun clear() {
        draft.value = null
    }

    fun change(key: String, value: JsonElement) {
        draft.update { it?.plus(key to value) }
    }

    fun save(settings: NovelSettings.LnSchema, onSaved: () -> Unit) {
        val changed = draft.value.orEmpty().filter { (key, value) -> loaded[key] != value }
        viewModelScope.launchIO {
            changed.forEach { (key, value) -> settings.set(key, value) }
            withUIContext { onSaved() }
        }
    }
}
