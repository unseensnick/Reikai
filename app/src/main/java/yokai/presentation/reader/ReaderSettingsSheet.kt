package yokai.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatAlignLeft
import androidx.compose.material.icons.automirrored.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.system.getResourceColor

/**
 * Reader display + theme settings. Tabbed (Display / Theme), adaptive (bottom sheet on phones,
 * centered dialog on tablets), mirroring [yokai.presentation.details.DetailsFilterSortSheet]. Holds
 * no prefs: the caller's ScreenModel persists changes and feeds new [settings] back in, which the
 * reader pushes to the WebView live.
 */
// no ScreenModel: pure UI, state owned by the caller's ScreenModel.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsSheet(
    settings: ReaderSettings,
    onFontSize: (Int) -> Unit,
    onLineHeight: (Float) -> Unit,
    onTextAlign: (String) -> Unit,
    onPadding: (Int) -> Unit,
    onFontFamily: (String) -> Unit,
    onFollowSystem: () -> Unit,
    onPreset: (ReaderThemePreset) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by rememberSaveable { mutableIntStateOf(TAB_DISPLAY) }
    val maxSheetHeight = (LocalConfiguration.current.screenHeightDp / 2).dp
    val context = LocalContext.current
    val sheetContainerColor = remember(context) { Color(context.getResourceColor(R.attr.background)) }
    val isTabletUi = LocalConfiguration.current.screenWidthDp >= 600

    val sheetBody: @Composable () -> Unit = {
        Column {
            SecondaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color.Transparent,
                indicator = {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(selectedTab),
                        color = MaterialTheme.colorScheme.primary,
                    )
                },
                divider = {},
            ) {
                TabLabel("Display", selectedTab == TAB_DISPLAY) { selectedTab = TAB_DISPLAY }
                TabLabel("Theme", selectedTab == TAB_THEME) { selectedTab = TAB_THEME }
            }
            HorizontalDivider()
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = maxSheetHeight)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                when (selectedTab) {
                    TAB_DISPLAY -> {
                        LabeledSlider(
                            label = "Font size",
                            value = "${settings.fontSize}",
                            sliderValue = settings.fontSize.toFloat(),
                            range = 12f..32f,
                            steps = 19,
                            onChange = { onFontSize(it.toInt()) },
                        )
                        LabeledSlider(
                            label = "Line height",
                            value = "%.1f".format(settings.lineHeight),
                            sliderValue = settings.lineHeight,
                            range = 1.0f..2.5f,
                            steps = 14,
                            onChange = { onLineHeight((it * 10).toInt() / 10f) },
                        )
                        LabeledSlider(
                            label = "Padding",
                            value = "${settings.padding}",
                            sliderValue = settings.padding.toFloat(),
                            range = 0f..48f,
                            steps = 11,
                            onChange = { onPadding(it.toInt()) },
                        )
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
                        Text(
                            "Font",
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState())
                                .padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            readerFonts.forEach { font ->
                                // Preview each chip in its own bundled font. Compose caches the parsed
                                // typeface, so the only cost is a one-time parse on first render.
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
                    }
                    TAB_THEME -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onFollowSystem)
                                .padding(vertical = 12.dp),
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

    if (isTabletUi) {
        Dialog(
            onDismissRequest = onDismiss,
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Surface(
                modifier = Modifier.padding(16.dp).widthIn(max = 460.dp),
                shape = RoundedCornerShape(28.dp),
                color = sheetContainerColor,
                shadowElevation = 6.dp,
                tonalElevation = 0.dp,
            ) {
                sheetBody()
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
            dragHandle = null,
            containerColor = sheetContainerColor,
        ) {
            sheetBody()
        }
    }
}

private const val TAB_DISPLAY = 0
private const val TAB_THEME = 1

@Composable
private fun TabLabel(label: String, selected: Boolean, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = { Text(label, style = MaterialTheme.typography.labelLarge) },
    )
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
private fun AlignButton(
    icon: ImageVector,
    value: String,
    current: String,
    onClick: (String) -> Unit,
) {
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
