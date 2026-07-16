package eu.kanade.presentation.manga

import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import reikai.presentation.notes.EntryNotesScreen

@Composable
fun MangaNotesScreen(
    state: MangaNotesScreen.State,
    navigateUp: () -> Unit,
    onUpdate: (String) -> Unit,
) {
    // RK: delegate to the shared manga/novel notes editor so the two catalogues can't drift; this
    // wrapper is the manga state -> primitives mapper.
    EntryNotesScreen(
        subtitle = state.manga.title,
        notes = state.notes,
        navigateUp = navigateUp,
        onUpdate = onUpdate,
    )
}
