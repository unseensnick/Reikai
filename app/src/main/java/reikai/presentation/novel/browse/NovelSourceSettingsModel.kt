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

    fun load(settings: NovelSettings.LnSchema) {
        clear()
        viewModelScope.launchIO {
            draft.value = settings.schema.mapNotNull { (key, schema) ->
                val declared = schema as? JsonObject ?: return@mapNotNull null
                (settings.get(key) ?: declared["value"])?.let { key to it }
            }.toMap()
        }
    }

    fun clear() {
        draft.value = null
    }

    fun change(key: String, value: JsonElement) {
        draft.update { it?.plus(key to value) }
    }

    fun save(settings: NovelSettings.LnSchema, onSaved: () -> Unit) {
        val values = draft.value.orEmpty()
        viewModelScope.launchIO {
            values.forEach { (key, value) -> settings.set(key, value) }
            withUIContext { onSaved() }
        }
    }
}
