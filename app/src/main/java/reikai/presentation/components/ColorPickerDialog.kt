package reikai.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.roundToInt

/**
 * Six hex digits, alpha dropped: the form the picker writes. Both reader renderers read stored colours
 * through `readerColorOrNull`, so they draw this value alike.
 */
fun Int.toHexRgb(): String = "#%06X".format(this and 0xFFFFFF)

/** Six hex digits, with or without a leading `#`, as an opaque colour. Null for anything else. */
fun parseHexRgb(text: String): Int? =
    text.removePrefix("#").takeIf { hexRgbPattern.matches(it) }?.toInt(16)?.let { it or OPAQUE }

private val hexRgbPattern = Regex("^[0-9a-fA-F]{6}$")
private const val OPAQUE = 0xFF000000.toInt()

/** An opaque colour picked by red, green and blue sliders or typed as hex, the two kept in step. */
@Composable
fun ColorPickerDialog(
    title: String,
    initialColor: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var color by remember { mutableIntStateOf(initialColor or OPAQUE) }
    var hex by remember { mutableStateOf(color.toHexRgb().drop(1)) }
    val hexValid = parseHexRgb(hex) != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(MaterialTheme.shapes.medium)
                        .background(Color(color))
                        .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium),
                )
                listOf(
                    MR.strings.color_filter_r_value to 16,
                    MR.strings.color_filter_g_value to 8,
                    MR.strings.color_filter_b_value to 0,
                ).forEach { (labelRes, shift) ->
                    val channel = (color shr shift) and 0xFF
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(labelRes), modifier = Modifier.width(56.dp))
                        Slider(
                            value = channel.toFloat(),
                            onValueChange = { value ->
                                color = (color and (0xFF shl shift).inv()) or (value.roundToInt() shl shift)
                                hex = color.toHexRgb().drop(1)
                            },
                            valueRange = 0f..255f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(channel.toString(), modifier = Modifier.widthIn(min = 36.dp))
                    }
                }
                OutlinedTextField(
                    value = hex,
                    onValueChange = { text ->
                        // Not upper-cased here: rewriting the text under the keyboard loses keystrokes.
                        hex = text.filter { it.isLetterOrDigit() }.take(6)
                        parseHexRgb(hex)?.let { color = it }
                    },
                    label = { Text(stringResource(MR.strings.color_hex)) },
                    prefix = { Text("#") },
                    singleLine = true,
                    isError = !hexValid,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(color) }, enabled = hexValid) {
                Text(stringResource(MR.strings.action_ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
        },
    )
}
