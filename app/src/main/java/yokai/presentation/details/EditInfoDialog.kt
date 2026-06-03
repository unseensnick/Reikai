package yokai.presentation.details

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Neutral dialog for overriding details metadata (title, author, artist, description, genre), fed
 * with initial values so both the manga and novel detail screens reuse it. Fields left blank pass
 * null to [onConfirm], which the caller treats as "clear the override / use the source value again".
 * Custom cover art is not handled here.
 */
@Composable
fun EditInfoDialog(
    initialTitle: String,
    initialAuthor: String,
    initialArtist: String,
    initialDescription: String,
    initialGenre: String,
    onDismiss: () -> Unit,
    onConfirm: (title: String?, author: String?, artist: String?, description: String?, genre: String?) -> Unit,
) {
    var title by remember { mutableStateOf(initialTitle) }
    var author by remember { mutableStateOf(initialAuthor) }
    var artist by remember { mutableStateOf(initialArtist) }
    var description by remember { mutableStateOf(initialDescription) }
    var genre by remember { mutableStateOf(initialGenre) }

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
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("Author") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    maxLines = 5,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = genre,
                    onValueChange = { genre = it },
                    label = { Text("Genres (comma-separated)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
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
                        genre.trim().takeIf { it.isNotBlank() },
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
