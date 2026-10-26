package reikai.presentation.reader.settings

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuShader
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuShaders
import eu.kanade.tachiyomi.ui.reader.viewer.webgpu.WebGpuViewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.DragHandle
import sh.calvin.reorderable.ReorderableColumn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState

/**
 * The WebGpu viewer's shader filters, which no other viewer runs, so nothing shows for those. Mihon's
 * shader lists from its WebGpuViewerSettings, whose sheet this one replaced.
 */
@Composable
internal fun ColumnScope.MangaShaderFilters(viewModel: ReaderSettingsViewModel) {
    val viewer by viewModel.viewerFlow.collectAsState()
    if (viewer !is WebGpuViewer) return

    val preferences = viewModel.preferences
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        WebGpuShaders.prune(context, listOf(preferences.imageFilters, preferences.tileFilters))
    }
    ShaderList(MR.strings.pref_image_filters, preferences.imageFilters)
    ShaderList(MR.strings.pref_tile_filters, preferences.tileFilters)
}

@Composable
private fun ShaderList(labelRes: StringResource, pref: Preference<List<WebGpuShader>>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val filters by pref.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    val pickShader = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                WebGpuShaders.import(context, uri, pref)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN, e)
                error = e.message ?: e.toString()
            }
        }
    }

    error?.let { message ->
        AlertDialog(
            onDismissRequest = { error = null },
            confirmButton = {
                TextButton(onClick = { error = null }) {
                    Text(stringResource(MR.strings.action_ok))
                }
            },
            title = { Text(stringResource(MR.strings.image_filter_invalid)) },
            text = {
                SelectionContainer(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        text = message,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
        )
    }

    HeadingItem(labelRes)
    ReorderableColumn(
        list = filters,
        onSettle = { from, to ->
            // The indices are into the list as drawn, which the preference may have moved past.
            val moved = filters.toMutableList().apply { add(to, removeAt(from)) }
            val current = pref.get()
            val files = current.mapTo(HashSet()) { it.file }
            val kept = moved.filter { it.file in files }
            val keptFiles = kept.mapTo(HashSet()) { it.file }
            pref.set(kept + current.filter { it.file !in keptFiles })
        },
    ) { _, shader, _ ->
        key(shader.file) {
            ReorderableItem {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = SettingsItemsPaddings.Horizontal, end = MaterialTheme.padding.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.DragHandle,
                        contentDescription = null,
                        modifier = Modifier.draggableHandle(),
                    )
                    Text(
                        text = shader.name,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = MaterialTheme.padding.medium),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(
                        onClick = { WebGpuShaders.remove(context, pref, shader) },
                    ) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Delete,
                            contentDescription = stringResource(MR.strings.action_delete),
                        )
                    }
                }
            }
        }
    }
    TextButton(
        onClick = {
            try {
                pickShader.launch(arrayOf("*/*"))
            } catch (e: ActivityNotFoundException) {
                error = e.message ?: e.toString()
            }
        },
        modifier = Modifier.padding(horizontal = SettingsItemsPaddings.Horizontal - MaterialTheme.padding.small),
    ) {
        Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = null)
        Text(
            text = stringResource(MR.strings.action_add_shader),
            modifier = Modifier.padding(start = MaterialTheme.padding.small),
        )
    }
}
