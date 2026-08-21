package eu.kanade.presentation.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.tooling.preview.PreviewLightDark
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.reader.components.ModeSelectionDialog
import eu.kanade.presentation.theme.TachiyomiPreviewTheme
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsIconGrid
import tachiyomi.presentation.core.components.material.IconToggleButton
import tachiyomi.presentation.core.i18n.stringResource

private val ReadingModesWithoutDefault = ReadingMode.entries - ReadingMode.DEFAULT

@Composable
fun ReadingModeSelectDialog(
    onDismissRequest: () -> Unit,
    viewModel: ReaderSettingsViewModel,
    onChange: (StringResource) -> Unit,
) {
    val manga by viewModel.mangaFlow.collectAsState()
    val readingMode = remember(manga) { ReadingMode.fromPreference(manga?.readingMode?.toInt()) }
    // RK: what the reader actually resolved, which for a series on Default is the global default or
    // auto webtoon's pick. Default is not one of the tiles, so seeding from [readingMode] alone left
    // the grid with nothing highlighted and no way to tell what you were reading in.
    val resolvedReadingMode = remember(manga) {
        ReadingMode.fromPreference(viewModel.resolvedReadingMode())
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        DialogContent(
            readingMode = readingMode,
            resolvedReadingMode = resolvedReadingMode,
            onChangeReadingMode = {
                viewModel.onChangeReadingMode(it)
                onChange(it.stringRes)
                onDismissRequest()
            },
            onDismissRequest = onDismissRequest,
        )
    }
}

@Composable
private fun DialogContent(
    readingMode: ReadingMode,
    resolvedReadingMode: ReadingMode,
    onChangeReadingMode: (ReadingMode) -> Unit,
    onDismissRequest: () -> Unit,
) {
    var selected by remember { mutableStateOf(resolvedReadingMode) }

    ModeSelectionDialog(
        onUseDefault = { onChangeReadingMode(ReadingMode.DEFAULT) }.takeIf { readingMode != ReadingMode.DEFAULT },
        // RK: applying an inherited mode nobody touched would pin it to this series, turning a look
        // into a choice. Only a real change writes; otherwise Apply just closes.
        onApply = { if (selected != readingMode) onChangeReadingMode(selected) else onDismissRequest() },
    ) {
        SettingsIconGrid(MR.strings.pref_category_reading_mode) {
            items(ReadingModesWithoutDefault) { mode ->
                IconToggleButton(
                    checked = mode == selected,
                    onCheckedChange = {
                        selected = mode
                    },
                    modifier = Modifier.fillMaxWidth(),
                    imageVector = ImageVector.vectorResource(mode.iconRes),
                    title = stringResource(mode.stringRes),
                )
            }
        }
    }
}

@PreviewLightDark
@Composable
private fun DialogContentPreview() {
    TachiyomiPreviewTheme {
        Surface {
            Column {
                DialogContent(
                    readingMode = ReadingMode.DEFAULT,
                    resolvedReadingMode = ReadingMode.RIGHT_TO_LEFT,
                    onChangeReadingMode = {},
                    onDismissRequest = {},
                )

                DialogContent(
                    readingMode = ReadingMode.LEFT_TO_RIGHT,
                    resolvedReadingMode = ReadingMode.LEFT_TO_RIGHT,
                    onChangeReadingMode = {},
                    onDismissRequest = {},
                )
            }
        }
    }
}
