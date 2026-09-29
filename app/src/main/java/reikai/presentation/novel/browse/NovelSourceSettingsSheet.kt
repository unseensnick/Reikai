package reikai.presentation.novel.browse

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.presentation.components.AdaptiveSheet
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import reikai.novel.source.NovelSettings
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.CheckboxItem
import tachiyomi.presentation.core.components.TextItem
import tachiyomi.presentation.core.components.material.Button
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Renders a light-novel source's `plugin.pluginSettings` schema (login fields, base-URL pickers,
 * content toggles) through the same Mihon settings-item primitives as [NovelSourceFilterSheet]. Its
 * [NovelSourceSettingsModel] loads the stored values on every open and saves them to the plugin's
 * storage, where the plugin reads them at runtime. Setting types track
 * lnreader's: Switch / Select / CheckboxGroup / Text (default). Required for sources like Komga and
 * Linovelib that are non-functional without user-provided settings.
 */
@Composable
internal fun NovelSourceSettingsSheet(
    settings: NovelSettings.LnSchema,
    onDismiss: () -> Unit,
) {
    val schema = settings.schema
    val model = viewModel<NovelSourceSettingsModel>(factory = modelFactory)
    val draft by model.draft.collectAsStateWithLifecycle()

    DisposableEffect(settings) {
        model.load(settings)
        onDispose(model::clear)
    }

    AdaptiveSheet(onDismissRequest = onDismiss) {
        LazyColumn {
            stickyHeader {
                Row(
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.background)
                        .fillMaxWidth()
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(MR.strings.action_settings),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    Button(onClick = { model.save(settings, onSaved = onDismiss) }) {
                        Text(text = stringResource(MR.strings.action_save))
                    }
                }
            }

            val values = draft
            if (values == null) {
                item {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                return@LazyColumn
            }

            items(count = schema.size) { index ->
                val (key, schemaEl) = schema.entries.elementAt(index)
                val s = schemaEl as? JsonObject ?: return@items
                NovelSettingItem(
                    schema = s,
                    current = values[key] ?: s["value"],
                    onChange = { value -> model.change(key, value) },
                )
            }
        }
    }
}

// Built directly rather than by reflection; it needs nothing from the graph, so it is not bound there.
private val modelFactory = viewModelFactory { initializer { NovelSourceSettingsModel() } }

@Composable
private fun NovelSettingItem(
    schema: JsonObject,
    current: JsonElement?,
    onChange: (JsonElement) -> Unit,
) {
    val label = schema["label"]?.jsonPrimitive?.contentOrNull ?: ""
    when (schema["type"]?.jsonPrimitive?.contentOrNull) {
        "Switch" -> {
            val checked = (current as? JsonPrimitive)?.booleanOrNull ?: false
            CheckboxItem(label = label, checked = checked, onClick = { onChange(JsonPrimitive(!checked)) })
        }
        "Select" -> SchemaPickerRow(label, schema, current, onChange)
        "CheckboxGroup" -> SchemaCheckboxGroupRow(label, schema, current, onChange)
        else -> TextItem(
            label = label,
            value = (current as? JsonPrimitive)?.contentOrNull ?: "",
            onChange = { onChange(JsonPrimitive(it)) },
        )
    }
}
