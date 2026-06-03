package yokai.presentation.novel.browse

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import yokai.novel.source.NovelSource

/**
 * Renders a light-novel source's `plugin.pluginSettings` schema as a settings form (login fields,
 * base-URL pickers, content toggles) and persists values back into the plugin's storage scope via
 * [NovelSource.setSetting], so the plugin reads them at runtime. Current values are loaded from
 * storage on open, falling back to each setting's declared default. Reuses the form-control rows
 * from [NovelSourceFilterSheet]. Setting types track lnreader's: Switch / Select / CheckboxGroup /
 * Text (default).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelSourceSettingsSheet(
    source: NovelSource,
    onDismiss: () -> Unit,
) {
    val schema = source.pluginSettings ?: return
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var draft by remember { mutableStateOf<Map<String, JsonElement>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(source.id) {
        val map = mutableMapOf<String, JsonElement>()
        schema.forEach { (key, schemaEl) ->
            val s = schemaEl as? JsonObject ?: return@forEach
            (source.getSetting(key) ?: s["value"])?.let { map[key] = it }
        }
        draft = map
        loaded = true
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Text("Source settings", style = MaterialTheme.typography.titleMedium)
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            if (!loaded) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                return@Column
            }

            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                schema.forEach { (key, schemaEl) ->
                    val s = schemaEl as? JsonObject ?: return@forEach
                    val label = s["label"]?.jsonPrimitive?.contentOrNull ?: key
                    val current = draft[key] ?: s["value"]
                    when (s["type"]?.jsonPrimitive?.contentOrNull) {
                        "Switch" -> FilterSwitchRow(
                            label = label,
                            checked = (current as? JsonPrimitive)?.booleanOrNull ?: false,
                            onChange = { draft = draft + (key to JsonPrimitive(it)) },
                        )
                        "Select" -> FilterPickerRow(
                            label = label,
                            options = optionsOf(s),
                            selectedValue = (current as? JsonPrimitive)?.contentOrNull ?: "",
                            onSelect = { draft = draft + (key to JsonPrimitive(it)) },
                        )
                        "CheckboxGroup" -> FilterCheckboxRow(
                            label = label,
                            options = optionsOf(s),
                            selected = (current as? JsonArray)
                                ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet(),
                            onChange = { sel -> draft = draft + (key to JsonArray(sel.map { JsonPrimitive(it) })) },
                        )
                        else -> FilterTextRow(
                            label = label,
                            value = (current as? JsonPrimitive)?.contentOrNull ?: "",
                            onChange = { draft = draft + (key to JsonPrimitive(it)) },
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    draft.forEach { (k, v) -> source.setSetting(k, v) }
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Save") }
        }
    }
}
