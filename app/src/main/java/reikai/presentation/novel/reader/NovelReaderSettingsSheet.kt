package reikai.presentation.novel.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.TabbedDialog
import eu.kanade.presentation.components.TabbedDialogPaddings
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Reader display + theme settings, hosted in the same [TabbedDialog] as the S3c chapter-settings
 * sheet (Display / Theme tabs) so the reader's chrome stays cohesive with the rest of the app. Holds
 * no prefs: the caller's ScreenModel persists changes and feeds new [settings] back in, which the
 * reader pushes to the WebView live.
 */
// no ScreenModel: pure UI, state owned by the caller's ScreenModel.
@Composable
fun NovelReaderSettingsSheet(
    settings: NovelReaderSettings,
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
    onTextAlign: (String) -> Unit,
    onPadding: (Int) -> Unit,
    onFontFamily: (String) -> Unit,
    onFollowSystem: () -> Unit,
    onPreset: (ReaderThemePreset) -> Unit,
    onKeepScreenOn: (Boolean) -> Unit,
    onOrientation: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    TabbedDialog(
        onDismissRequest = onDismiss,
        tabTitles = listOf("Display", "Theme"),
    ) { page ->
        Column(
            modifier = Modifier
                .padding(vertical = TabbedDialogPaddings.Vertical)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            when (page) {
                0 -> {
                    LabeledSlider("Font size", "${settings.fontSize}", settings.fontSize.toFloat(), 12f..32f, 19) { onFontSize(it.toInt()) }
                    LabeledSlider("Line height", "%.1f".format(settings.lineHeight), settings.lineHeight, 1.0f..2.5f, 14) { onLineHeight((it * 10).toInt() / 10f) }
                    LabeledSlider("Padding", "${settings.padding}", settings.padding.toFloat(), 0f..48f, 11) { onPadding(it.toInt()) }
                    Text("Alignment", style = MaterialTheme.typography.titleSmall)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        AlignButton(Icons.AutoMirrored.Filled.FormatAlignLeft, "left", settings.textAlign, onTextAlign)
                        AlignButton(Icons.Filled.FormatAlignCenter, "center", settings.textAlign, onTextAlign)
                        AlignButton(Icons.Filled.FormatAlignJustify, "justify", settings.textAlign, onTextAlign)
                        AlignButton(Icons.AutoMirrored.Filled.FormatAlignRight, "right", settings.textAlign, onTextAlign)
                    }
                    Text("Font", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        readerFonts.forEach { font ->
                            // Preview each chip in its own bundled font; Compose caches the typeface.
                            val fontFamily = remember(font.family) {
                                if (font.family.isEmpty()) {
                                    FontFamily.Default
                                } else {
                                    FontFamily(Font(path = "fonts/${font.family}.ttf", assetManager = context.assets))
                                }
                            }
                            FilterChip(
                                selected = settings.fontFamily == font.family,
                                onClick = { onFontFamily(font.family) },
                                label = { Text(font.name, fontFamily = fontFamily) },
                            )
                        }
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onKeepScreenOn(!settings.keepScreenOn) }
                            .padding(top = 8.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text("Keep screen on", style = MaterialTheme.typography.bodyLarge)
                        Switch(checked = settings.keepScreenOn, onCheckedChange = onKeepScreenOn)
                    }
                    // Per-novel orientation: Default (follow the global default) + the concrete locks.
                    Text("Orientation", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        readerOrientations.forEach { orientation ->
                            FilterChip(
                                selected = settings.orientation == orientation.flagValue,
                                onClick = { onOrientation(orientation.flagValue) },
                                leadingIcon = { Icon(orientation.icon, contentDescription = null) },
                                label = { Text(stringResource(orientation.stringRes)) },
                            )
                        }
                    }
                }
                1 -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable(onClick = onFollowSystem).padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        RadioButton(selected = settings.followSystemTheme, onClick = null)
                        Text("Follow system (auto light / dark)", style = MaterialTheme.typography.bodyLarge)
                    }
                    Text("Presets", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        readerThemePresets.forEach { preset ->
                            val selected = !settings.followSystemTheme &&
                                settings.backgroundColor.equals(preset.background, ignoreCase = true)
                            PresetSwatch(preset, selected) { onPreset(preset) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: String,
    sliderValue: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Text("$label: $value", style = MaterialTheme.typography.titleSmall)
    Slider(
        value = sliderValue.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
    )
}

@Composable
private fun AlignButton(icon: ImageVector, value: String, current: String, onClick: (String) -> Unit) {
    val selected = current == value
    IconButton(onClick = { onClick(value) }) {
        Icon(
            imageVector = icon,
            contentDescription = value,
            tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PresetSwatch(preset: ReaderThemePreset, selected: Boolean, onClick: () -> Unit) {
    val bg = remember(preset.background) { Color(android.graphics.Color.parseColor(preset.background)) }
    val fg = remember(preset.textColor) { Color(android.graphics.Color.parseColor(preset.textColor)) }
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
