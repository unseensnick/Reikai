package eu.kanade.presentation.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import dev.icerock.moko.resources.StringResource
import eu.kanade.domain.manga.model.readingMode
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsViewModel
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import reikai.presentation.reader.ReaderModeDialog
import tachiyomi.i18n.MR

private val ReadingModesWithoutDefault = ReadingMode.entries - ReadingMode.DEFAULT

@Composable
fun ReadingModeSelectDialog(
    onDismissRequest: () -> Unit,
    viewModel: ReaderSettingsViewModel,
    onChange: (StringResource) -> Unit,
) {
    val manga by viewModel.mangaFlow.collectAsState()
    val readingMode = remember(manga) { ReadingMode.fromPreference(manga?.readingMode?.toInt()) }
    // RK --> the picker rules are ReaderModeDialog's, which the novel bar's picker and rotation share, so
    // upstream's DialogContent and its preview went with them. The grid highlights what the reader
    // resolved, since Default is not a tile.
    val resolvedReadingMode = remember(manga) {
        ReadingMode.fromPreference(viewModel.resolvedReadingMode())
    }

    ReaderModeDialog(
        title = MR.strings.pref_category_reading_mode,
        choices = ReadingModesWithoutDefault,
        stored = readingMode.takeIf { it != ReadingMode.DEFAULT },
        resolved = resolvedReadingMode,
        icon = { it.icon },
        label = { it.stringRes },
        onChange = {
            val mode = it ?: ReadingMode.DEFAULT
            viewModel.setReadingMode(mode)
            onChange(mode.stringRes)
        },
        onDismiss = onDismissRequest,
    )
    // RK <--
}
