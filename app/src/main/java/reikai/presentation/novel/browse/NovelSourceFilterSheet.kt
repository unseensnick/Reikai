package reikai.presentation.novel.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tachiyomi.core.common.preference.TriState
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SelectItem
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.components.TriStateItem
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Renders a light-novel source's `plugin.filters` schema as the same controls the manga filter sheet
 * uses ([eu.kanade.tachiyomi.ui.browse.source.browse.SourceFilterDialog]): an [AdaptiveSheet] of
 * Mihon's settings-item primitives. Each control emits a JsonElement of the shape the plugin reads at
 * `options.filters.X.value`: a boolean for Switch/XCheckbox, a string for Text/Picker, a string array
 * for Checkbox, and `{include, exclude}` for ExcludableCheckboxGroup. Pure render + emit; the model
 * owns the value map and refetches on apply.
 */
@Composable
internal fun NovelSourceFilterSheet(
    filters: JsonObject?,
    values: Map<String, JsonElement>,
    onValueChange: (key: String, value: JsonElement) -> Unit,
    onApply: () -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit,
) {
    val entries = filters.orEmpty().mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it } }
    AdaptiveSheet(onDismissRequest = onDismiss) {
        LazyColumn {
            stickyHeader {
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .padding(8.dp),
                ) {
                    TextButton(onClick = onReset) {
                        Text(
                            text = stringResource(MR.strings.action_reset),
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = {
                        onApply()
                        onDismiss()
                    }) {
                        Text(text = stringResource(MR.strings.action_filter))
                    }
                }
            }
            items(items = entries, key = { it.first }) { (key, schema) ->
                NovelFilterItem(
                    schema = schema,
                    current = values[key] ?: schema["value"],
                    onValueChange = { value -> onValueChange(key, value) },
                )
            }
        }
    }
}

@Composable
private fun NovelFilterItem(
    schema: JsonObject,
    current: JsonElement?,
    onValueChange: (JsonElement) -> Unit,
) {
    val label = schema.label()
    when (schema["type"]?.jsonPrimitive?.contentOrNull) {
        "Switch", "XCheckbox" -> {
            val checked = (current as? JsonPrimitive)?.booleanOrNull ?: false
            CheckboxItem(label = label, checked = checked, onClick = { onValueChange(JsonPrimitive(!checked)) })
        }
        "TextInput", "Text" -> TextItem(
            label = label,
            value = (current as? JsonPrimitive)?.contentOrNull ?: "",
            onChange = { onValueChange(JsonPrimitive(it)) },
        )
        "Picker" -> {
            val options = optionsOf(schema)
            val selectedValue = (current as? JsonPrimitive)?.contentOrNull ?: ""
            val selectedIndex = options.indexOfFirst { it.second == selectedValue }.coerceAtLeast(0)
            SelectItem(
                label = label,
                options = options.map { it.first }.toTypedArray(),
                selectedIndex = selectedIndex,
                onSelect = { index -> options.getOrNull(index)?.let { onValueChange(JsonPrimitive(it.second)) } },
            )
        }
        "Checkbox" -> {
            val selected = (current as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet() ?: emptySet()
            Column {
                HeadingItem(text = label)
                optionsOf(schema).forEach { (optLabel, optValue) ->
                    val checked = optValue in selected
                    CheckboxItem(
                        label = optLabel,
                        checked = checked,
                        onClick = {
                            val next = if (checked) selected - optValue else selected + optValue
                            onValueChange(JsonArray(next.map { JsonPrimitive(it) }))
                        },
                    )
                }
            }
        }
        "ExcludableCheckboxGroup" -> {
            val obj = current as? JsonObject
            val include =
                (obj?.get("include") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
                    ?: emptySet()
            val exclude =
                (obj?.get("exclude") as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }?.toSet()
                    ?: emptySet()
            Column {
                HeadingItem(text = label)
                optionsOf(schema).forEach { (optLabel, optValue) ->
                    val triState = when (optValue) {
                        in include -> TriState.ENABLED_IS
                        in exclude -> TriState.ENABLED_NOT
                        else -> TriState.DISABLED
                    }
                    TriStateItem(
                        label = optLabel,
                        state = triState,
                        onClick = {
                            // Off -> include -> exclude -> Off, then re-emit both lists.
                            val (inc, exc) = when (triState) {
                                TriState.DISABLED -> (include + optValue) to exclude
                                TriState.ENABLED_IS -> (include - optValue) to (exclude + optValue)
                                TriState.ENABLED_NOT -> include to (exclude - optValue)
                            }
                            onValueChange(
                                buildJsonObject {
                                    put("include", JsonArray(inc.map { JsonPrimitive(it) }))
                                    put("exclude", JsonArray(exc.map { JsonPrimitive(it) }))
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

private fun JsonObject.label(): String =
    this["label"]?.jsonPrimitive?.contentOrNull ?: this["type"]?.jsonPrimitive?.contentOrNull ?: ""

/** Extracts a schema's `options` array (`[{label, value}, ...]`) as label/value pairs. */
internal fun optionsOf(schema: JsonObject): List<Pair<String, String>> {
    val arr = schema["options"] as? JsonArray ?: return emptyList()
    return arr.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val value = o["value"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        (o["label"]?.jsonPrimitive?.contentOrNull ?: value) to value
    }
}
