package reikai.presentation.reader

import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import tachiyomi.i18n.MR

/**
 * Orientation picker for the reader's rotation button, both content types. Takes the entry's own flag
 * and hands one back, so the caller owns where it is stored; Mihon's `OrientationSelectDialog`, which
 * read the manga off a settings model, was deleted for it.
 */
@Composable
fun ReaderOrientationDialog(
    currentOrientation: Int,
    resolvedOrientation: Int,
    onChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    ReaderModeDialog(
        title = MR.strings.rotation_type,
        choices = readerOrientationChoices,
        stored = ReaderOrientation.fromPreference(currentOrientation).takeIf { it != ReaderOrientation.DEFAULT },
        resolved = ReaderOrientation.fromPreference(resolvedOrientation),
        icon = { it.icon },
        label = { it.stringRes },
        onChange = { onChange((it ?: ReaderOrientation.DEFAULT).flagValue) },
        onDismiss = onDismiss,
    )
}
