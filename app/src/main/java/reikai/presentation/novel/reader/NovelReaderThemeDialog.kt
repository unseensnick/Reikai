package reikai.presentation.novel.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Theme picker for the novel reader's bottom-bar theme button: the follow-system toggle plus the reader
 * color presets, the same controls as the settings sheet's Display tab but reachable in one tap. Changes
 * apply live (the caller's ScreenModel persists them and the reader re-themes in place).
 */
@Composable
fun NovelReaderThemeDialog(
    followSystemTheme: Boolean,
    backgroundColor: String,
    onFollowSystem: () -> Unit,
    onPreset: (ReaderThemePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(stringResource(MR.strings.pref_category_theme), style = MaterialTheme.typography.titleMedium)
            Row(
                modifier = Modifier.fillMaxWidth().clickable(onClick = onFollowSystem).padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                RadioButton(selected = followSystemTheme, onClick = null)
                Text("Follow system (auto light / dark)", style = MaterialTheme.typography.bodyLarge)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                readerThemePresets.forEach { preset ->
                    val selected = !followSystemTheme && backgroundColor.equals(preset.background, ignoreCase = true)
                    PresetSwatch(preset, selected) { onPreset(preset) }
                }
            }
        }
    }
}
