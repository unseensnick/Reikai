package eu.kanade.presentation.manga

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.manga.components.MangaNotesTextArea
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesViewModel
import reikai.presentation.notes.EntryNotesScreen
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun MangaNotesScreen(
    state: MangaNotesViewModel.State,
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
