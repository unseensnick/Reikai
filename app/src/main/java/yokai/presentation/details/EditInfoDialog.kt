package yokai.presentation.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Neutral dialog for overriding details metadata, fed with initial values so both the manga and novel
 * detail screens reuse it. Blank text fields pass null to [onConfirm] ("clear the override / use the
 * source value"). Genres are edited as removable chips; [onReset] clears every override at once.
 * [statusOptions] (value to label) enables a manga-only status override; null hides it. Custom cover
 * art is handled by the cover viewer, not here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditInfoDialog(
    initialTitle: String,
    initialAuthor: String,
    initialArtist: String,
    initialDescription: String,
    initialGenre: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String?, author: String?, artist: String?, description: String?, genre: String?, status: Int?) -> Unit,
    statusOptions: List<Pair<Int, String>>? = null,
    initialStatus: Int = -1,
    onReset: (() -> Unit)? = null,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var author by remember { mutableStateOf(initialAuthor) }
    var artist by remember { mutableStateOf(initialArtist) }
    var description by remember { mutableStateOf(initialDescription) }
    val genres = remember {
        mutableStateListOf(*initialGenre.split(",").map { it.trim() }.filter { it.isNotBlank() }.toTypedArray())
    }
    var newGenre by remember { mutableStateOf("") }
    var status by remember { mutableStateOf(initialStatus) }
    var statusMenuOpen by remember { mutableStateOf(false) }

    fun addGenre() {
        val value = newGenre.trim()
        if (value.isNotBlank() && genres.none { it.equals(value, ignoreCase = true) }) genres.add(value)
        newGenre = ""
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit info") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(vertical = 4.dp),
            ) {
                EditField(title, "Title") { title = it }
                Spacer(Modifier.height(8.dp))
                EditField(author, "Author") { author = it }
                Spacer(Modifier.height(8.dp))
                EditField(artist, "Artist") { artist = it }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                )

                if (statusOptions != null) {
                    Spacer(Modifier.height(8.dp))
                    Box {
                        OutlinedTextField(
                            value = statusOptions.firstOrNull { it.first == status }?.second ?: "Unknown",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Status") },
                            trailingIcon = { Icon(Icons.Outlined.ArrowDropDown, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        // Transparent overlay so a tap anywhere on the (read-only) field opens the menu,
                        // not just the dropdown icon.
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                ) { statusMenuOpen = true },
                        )
                        DropdownMenu(expanded = statusMenuOpen, onDismissRequest = { statusMenuOpen = false }) {
                            statusOptions.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = { status = value; statusMenuOpen = false },
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                Text("Genres", style = MaterialTheme.typography.labelLarge)
                if (genres.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        genres.forEach { genre ->
                            InputChip(
                                selected = false,
                                onClick = { genres.remove(genre) },
                                label = { Text(genre) },
                                trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = "Remove $genre", modifier = Modifier.height(16.dp)) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = newGenre,
                    onValueChange = { newGenre = it },
                    label = { Text("Add genre") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = { addGenre() }) {
                            Icon(Icons.Outlined.Add, contentDescription = "Add genre")
                        }
                    },
                )

                if (onReset != null) {
                    Spacer(Modifier.height(4.dp))
                    TextButton(onClick = onReset) { Text("Reset to source values") }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        title.trim().takeIf { it.isNotBlank() },
                        author.trim().takeIf { it.isNotBlank() },
                        artist.trim().takeIf { it.isNotBlank() },
                        description.trim().takeIf { it.isNotBlank() },
                        genres.joinToString(", ").takeIf { it.isNotBlank() },
                        status.takeIf { statusOptions != null },
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun EditField(value: String, label: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}
