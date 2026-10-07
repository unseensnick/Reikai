package reikai.presentation.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.util.formatChapterNumber
import reikai.domain.chapter.ChapterNumberEdit
import reikai.domain.chapter.isRecognizedChapterNumber
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Corrects one chapter's number. [onSave] takes the number typed, or null to put the source's own back,
 * which only a chapter already corrected offers.
 */
@Composable
fun ChapterNumberDialog(
    edit: ChapterNumberEdit,
    onDismissRequest: () -> Unit,
    onSave: (Double?) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(edit.startingText()) }
    val number = parsedChapterNumber(text)
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(
                enabled = number != null,
                onClick = {
                    onSave(number)
                    onDismissRequest()
                },
            ) {
                Text(text = stringResource(MR.strings.action_save))
            }
        },
        dismissButton = {
            Row {
                if (edit.sourceNumber != null) {
                    TextButton(
                        onClick = {
                            onSave(null)
                            onDismissRequest()
                        },
                    ) {
                        Text(text = stringResource(MR.strings.action_reset))
                    }
                }
                TextButton(onClick = onDismissRequest) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            }
        },
        title = { Text(text = stringResource(MR.strings.action_correct_chapter_number)) },
        text = {
            Column {
                Text(text = edit.name, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    modifier = Modifier.focusRequester(focusRequester),
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(text = stringResource(MR.strings.show_chapter_number)) },
                    supportingText = edit.sourceNumber?.let { source ->
                        { Text(text = stringResource(MR.strings.chapter_number_source, formatChapterNumber(source))) }
                    },
                    isError = number == null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
    )

    LaunchedEffect(focusRequester) { focusRequester.requestFocus() }
}

/** The number [text] names, a comma read as the decimal point, or null when it names none a chapter can have. */
internal fun parsedChapterNumber(text: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && isRecognizedChapterNumber(it) }

/** The text the field opens on: the hint's suggestion when the chapter has one, else its number. */
internal fun ChapterNumberEdit.startingText(): String = formatChapterNumber(suggestion ?: number)
