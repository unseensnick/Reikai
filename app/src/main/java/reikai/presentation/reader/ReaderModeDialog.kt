package reikai.presentation.reader

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.reader.components.ModeSelectionDialog
import tachiyomi.presentation.core.components.SettingsIconGrid
import tachiyomi.presentation.core.components.material.IconToggleButton
import tachiyomi.presentation.core.i18n.stringResource

/**
 * A bar button's quick mode picker, for an entry's own mode: [stored], null while it follows the default.
 * The grid highlights [resolved], since Default has no tile, Revert to default shows only for a mode of its
 * own, and Apply writes only a tile the reader tapped, see [ModeSelection]. [onChange] gets null for Default.
 */
@Composable
internal fun <T : Any> ReaderModeDialog(
    title: StringResource,
    choices: List<T>,
    stored: T?,
    resolved: T,
    icon: (T) -> ImageVector,
    label: (T) -> StringResource,
    onChange: (T?) -> Unit,
    onDismiss: () -> Unit,
) {
    val selection = remember { ModeSelection<T?>() }
    AdaptiveSheet(onDismissRequest = onDismiss) {
        ModeSelectionDialog(
            onUseDefault = {
                onChange(null)
                onDismiss()
            }.takeIf { stored != null },
            onApply = {
                selection.toApply(stored)?.let(onChange)
                onDismiss()
            },
        ) {
            SettingsIconGrid(title) {
                items(choices) { mode ->
                    IconToggleButton(
                        checked = selection.isChecked(mode, resolved),
                        onCheckedChange = { selection.tap(mode) },
                        modifier = Modifier.fillMaxWidth(),
                        imageVector = icon(mode),
                        title = stringResource(label(mode)),
                    )
                }
            }
        }
    }
}
