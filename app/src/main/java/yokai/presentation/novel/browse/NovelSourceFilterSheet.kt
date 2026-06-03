package yokai.presentation.novel.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Renders a light-novel source's `plugin.filters` schema as interactive controls plus a popular /
 * latest toggle, the UI side of [buildOptions]. Each control writes a JsonElement of the shape the
 * plugin expects (`options.filters.X.value`): a string for Picker/Text, a boolean for Switch, a
 * string array for Checkbox, and `{include, exclude}` for ExcludableCheckboxGroup. The parent owns
 * the value map and re-fetches on [onApply]; this composable is pure render + emit.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NovelSourceFilterSheet(
    filters: JsonObject?,
    values: Map<String, JsonElement>,
    showLatest: Boolean,
    onValueChange: (key: String, value: JsonElement) -> Unit,
    onShowLatestChange: (Boolean) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Filters",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onReset) { Text("Reset") }
            }
            FilterSwitchRow("Show latest releases", showLatest, onShowLatestChange)

            val entries = filters.orEmpty().mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
            if (entries.isNotEmpty()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Column(
                    modifier = Modifier
                        .heightIn(max = 460.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    entries.forEach { (key, schema) ->
                        val label = schema["label"]?.jsonPrimitive?.contentOrNull ?: key
                        val current = values[key] ?: schema["value"]
                        when (schema["type"]?.jsonPrimitive?.contentOrNull) {
                            "Switch", "XCheckbox" -> FilterSwitchRow(
                                label = label,
                                checked = (current as? JsonPrimitive)?.booleanOrNull ?: false,
                                onChange = { onValueChange(key, JsonPrimitive(it)) },
                            )
                            "TextInput", "Text" -> FilterTextRow(
                                label = label,
                                value = (current as? JsonPrimitive)?.contentOrNull ?: "",
                                onChange = { onValueChange(key, JsonPrimitive(it)) },
                            )
                            "Picker" -> FilterPickerRow(
                                label = label,
                                options = optionsOf(schema),
                                selectedValue = (current as? JsonPrimitive)?.contentOrNull ?: "",
                                onSelect = { onValueChange(key, JsonPrimitive(it)) },
                            )
                            "Checkbox" -> FilterCheckboxRow(
                                label = label,
                                options = optionsOf(schema),
                                selected = (current as? JsonArray)
                                    ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet(),
                                onChange = { sel -> onValueChange(key, JsonArray(sel.map { JsonPrimitive(it) })) },
                            )
                            "ExcludableCheckboxGroup" -> FilterExcludableRow(
                                label = label,
                                options = optionsOf(schema),
                                current = current as? JsonObject,
                                onChange = { inc, exc ->
                                    onValueChange(
                                        key,
                                        buildJsonObject {
                                            put("include", JsonArray(inc.map { JsonPrimitive(it) }))
                                            put("exclude", JsonArray(exc.map { JsonPrimitive(it) }))
                                        },
                                    )
                                },
                            )
                            else -> {}
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            Button(onClick = onApply, modifier = Modifier.fillMaxWidth()) { Text("Apply") }
        }
    }
}

/** Extracts a filter's `options` array (`[{label, value}, ...]`) as label/value pairs. */
private fun optionsOf(schema: JsonObject): List<Pair<String, String>> {
    val arr = schema["options"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val value = o["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        (o["label"]?.jsonPrimitive?.contentOrNull ?: value) to value
    }
}

@Composable
private fun FilterSwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun FilterTextRow(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

@Composable
private fun FilterPickerRow(
    label: String,
    options: List<Pair<String, String>>,
    selectedValue: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.second == selectedValue }?.first ?: selectedValue
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLabel.ifBlank { "Any" }, modifier = Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                options.forEach { (optLabel, optValue) ->
                    DropdownMenuItem(
                        text = { Text(optLabel) },
                        onClick = { onSelect(optValue); expanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterCheckboxRow(
    label: String,
    options: List<Pair<String, String>>,
    selected: Set<String>,
    onChange: (Set<String>) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        options.forEach { (optLabel, optValue) ->
            val checked = optValue in selected
            val toggle = { onChange(if (checked) selected - optValue else selected + optValue) }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { toggle() }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { toggle() })
                Text(optLabel, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

@Composable
private fun FilterExcludableRow(
    label: String,
    options: List<Pair<String, String>>,
    current: JsonObject?,
    onChange: (include: Set<String>, exclude: Set<String>) -> Unit,
) {
    val include = (current?.get("include") as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()
    val exclude = (current?.get("exclude") as? JsonArray)
        ?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        options.forEach { (optLabel, optValue) ->
            val triState = when (optValue) {
                in include -> ToggleableState.On
                in exclude -> ToggleableState.Indeterminate
                else -> ToggleableState.Off
            }
            // Cycle Off -> include (On) -> exclude (Indeterminate) -> Off.
            val cycle = {
                when (triState) {
                    ToggleableState.Off -> onChange(include + optValue, exclude)
                    ToggleableState.On -> onChange(include - optValue, exclude + optValue)
                    ToggleableState.Indeterminate -> onChange(include, exclude - optValue)
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { cycle() }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TriStateCheckbox(state = triState, onClick = { cycle() })
                Text(optLabel, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}
