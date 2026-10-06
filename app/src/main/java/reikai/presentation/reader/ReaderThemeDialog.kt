package reikai.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AdaptiveSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Theme picker for the novel reader's bottom-bar theme button: the follow-system toggle plus the reader
 * color presets, reachable in one tap. Changes apply live (the caller persists them and the reader
 * re-themes in place).
 */
@Composable
fun ReaderThemeDialog(
    followSystemTheme: Boolean,
    backgroundColor: String,
    textColor: String,
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
                Text(
                    stringResource(MR.strings.pref_novel_theme_follow_system),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            ReaderThemeSwatches(
                followSystem = followSystemTheme,
                background = backgroundColor,
                textColor = textColor,
                onPreset = onPreset,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/** The preset swatches, as the theme button's sheet and the settings sheet's Appearance tab both show them. */
@Composable
fun ReaderThemeSwatches(
    followSystem: Boolean,
    background: String,
    textColor: String,
    onPreset: (ReaderThemePreset) -> Unit,
    modifier: Modifier = Modifier,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        readerThemePresets.forEach { preset ->
            PresetSwatch(preset, preset.isPickedBy(followSystem, background, textColor)) { onPreset(preset) }
        }
    }
}

@Composable
private fun PresetSwatch(preset: ReaderThemePreset, selected: Boolean, onClick: () -> Unit) {
    val bg = remember(preset.background) { Color(readerBackgroundColorInt(preset.background)) }
    val fg = remember(preset.textColor) { Color(readerTextColorInt(preset.textColor)) }
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(bg)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text("A", color = fg, fontWeight = FontWeight.Bold)
    }
}
