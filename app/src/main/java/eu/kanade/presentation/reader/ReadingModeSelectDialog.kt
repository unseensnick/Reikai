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
    // RK: what the reader actually resolved, which for a series on Default is the global default or
    // auto webtoon's pick. Default is not one of the tiles, so seeding from [readingMode] alone left
    // the grid with nothing highlighted and no way to tell what you were reading in.
    val resolvedReadingMode = remember(manga) {
        ReadingMode.fromPreference(viewModel.resolvedReadingMode())
    }

    // RK -->
    // The picker both readers' mode and rotation buttons share, so its rules are written once.
    ReaderModeDialog(
        title = MR.strings.pref_category_reading_mode,
        choices = ReadingModesWithoutDefault,
        stored = readingMode.takeIf { it != ReadingMode.DEFAULT },
        resolved = resolvedReadingMode,
        icon = { it.icon },
        label = { it.stringRes },
        onChange = { picked ->
            val mode = picked ?: ReadingMode.DEFAULT
            viewModel.setReadingMode(mode)
            onChange(mode.stringRes)
        },
        onDismiss = onDismissRequest,
    )
    // RK <--
}
