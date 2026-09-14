package reikai.presentation.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import reikai.presentation.icons.ReikaiIcons
import reikai.presentation.icons.Remove
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/*
 * The in-reader settings sheet's controls that Mihon's settings items do not have, ported from
 * tsundoku's SettingsItems.kt (b04f9a4d3) with its hardcoded labels moved onto string resources.
 */

/**
 * A label with - and + either side of the value, stepping by [step] within [valueRange]. Tapping the
 * value asks for one directly. A value stored scaled (tenths) passes [scale], so it is typed as it reads,
 * and [valueString] for how it is shown.
 */
@Composable
fun StepperItem(
    label: String,
    value: Int,
    onChange: (Int) -> Unit,
    valueRange: IntRange,
    step: Int = 1,
    defaultValue: Int? = null,
    scale: Int = 1,
    valueString: String = value.toString(),
) {
    var showDialog by remember { mutableStateOf(false) }
    if (showDialog) {
        StepperInputDialog(
            value = value,
            valueRange = valueRange,
            scale = scale,
            defaultValue = defaultValue,
            onDismiss = { showDialog = false },
            onConfirm = {
                onChange(it)
                showDialog = false
            },
        )
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = SettingsItemsPaddings.Vertical / 8),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onChange((value - step).coerceAtLeast(valueRange.first)) },
                enabled = value > valueRange.first,
            ) {
                Icon(ReikaiIcons.Remove, contentDescription = stringResource(MR.strings.action_decrease))
            }
            Text(
                text = valueString,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .widthIn(min = 32.dp)
                    .clickable { showDialog = true },
            )
            IconButton(
                onClick = { onChange((value + step).coerceAtMost(valueRange.last)) },
                enabled = value < valueRange.last,
            ) {
                Icon(MaterialSymbols.Rounded.Add, contentDescription = stringResource(MR.strings.action_increase))
            }
        }
    }
}

@Composable
private fun StepperInputDialog(
    value: Int,
    valueRange: IntRange,
    scale: Int,
    defaultValue: Int?,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var input by remember { mutableStateOf(unscaled(value, scale)) }
    val parsed = parseStepperInput(input, scale, valueRange)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    MR.strings.reader_settings_value_range,
                    unscaled(valueRange.first, scale),
                    unscaled(valueRange.last, scale),
                ),
            )
        },
        text = {
            OutlinedTextField(
                value = input,
                onValueChange = { text -> input = text.filter { it.isDigit() || (scale > 1 && it == '.') } },
                singleLine = true,
                isError = parsed == null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (scale > 1) KeyboardType.Decimal else KeyboardType.Number,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { parsed?.let(onConfirm) }, enabled = parsed != null) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            Row {
                if (defaultValue != null) {
                    TextButton(onClick = { onConfirm(defaultValue) }) {
                        Text(stringResource(MR.strings.label_default))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(MR.strings.action_cancel))
                }
            }
        },
    )
}

/** What a person types for a stepper holding [range] in units of 1/[scale], or null for anything outside it. */
internal fun parseStepperInput(input: String, scale: Int, range: IntRange): Int? =
    input.toFloatOrNull()?.let { (it * scale).roundToInt() }?.takeIf { it in range }

private fun unscaled(value: Int, scale: Int): String =
    if (scale == 1) value.toString() else (value.toFloat() / scale).toString()

/** A label with its chips on the same line, for a choice short enough to fit beside it. */
@Composable
fun InlineSettingsChipRow(label: String, content: @Composable FlowRowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal, vertical = SettingsItemsPaddings.Vertical / 4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small), content = content)
    }
}
