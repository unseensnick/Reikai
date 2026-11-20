package reikai.presentation.notes

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.manga.components.MangaNotesTextArea
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.Navigator
import reikai.domain.entry.EntryId
import reikai.presentation.details.shows
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * The single markdown notes editor for both manga and novels: an AppBar over the shared
 * [MangaNotesTextArea]. Takes primitives ([subtitle], [notes]) so either content type's screen maps
 * its own state into it, and the two catalogues can't drift.
 */
@Composable
fun EntryNotesScreen(
    subtitle: String,
    notes: String,
    navigateUp: () -> Unit,
    onUpdate: (String) -> Unit,
) {
    Scaffold(
        topBar = { topBarScrollBehavior ->
            AppBar(
                titleContent = {
                    AppBarTitle(
                        title = stringResource(MR.strings.action_edit_notes),
                        subtitle = subtitle,
                    )
                },
                navigateUp = navigateUp,
                scrollBehavior = topBarScrollBehavior,
            )
        },
    ) { contentPadding ->
        MangaNotesTextArea(
            notes = notes,
            onUpdate = onUpdate,
            modifier = Modifier
                .padding(contentPadding)
                .consumeWindowInsets(contentPadding)
                .imePadding(),
        )
    }
}

/**
 * [editor]'s page: loading until its notes are read, then the editor headed by [subtitle]. Closes by
 * [closeMissingNotes] once the notes turn out to be gone, with [series]' details page when it has one.
 */
@Composable
fun <S : Any> EntryNotesScreen(
    editor: NotesEditor<S>,
    subtitle: @Composable (S) -> String,
    series: EntryId?,
) {
    val navigator = LocalNavigator.current
    val missing by editor.missing.collectAsState()
    LaunchedEffect(missing) {
        if (missing) navigator.closeMissingNotes(series)
    }
    val state = editor.state.collectAsState().value
    if (state == null) {
        LoadingScreen()
        return
    }
    EntryNotesScreen(
        subtitle = subtitle(state.subject),
        notes = state.notes,
        navigateUp = navigator::pop,
        onUpdate = editor::update,
    )
}

/**
 * Closes a notes page whose subject is gone, and [series]' details page under it, which cannot load it either,
 * as upstream's manga notes page does. A volume's passes none, since its series may still be there.
 */
fun Navigator.closeMissingNotes(series: EntryId?) {
    pop()
    if (series != null) popTo { !it.shows(series) }
}
