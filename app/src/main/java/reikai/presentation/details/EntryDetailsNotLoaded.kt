package reikai.presentation.details

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AppBar
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.screens.EmptyScreen

/**
 * The details page before it has an entry to show, for both content types: the not-found page, else the
 * page's loading shape.
 */
@Composable
fun EntryDetailsNotLoaded(state: EntryDetailsScreenState, navigateUp: () -> Unit) {
    if (state == EntryDetailsScreenState.NotFound) {
        Scaffold(
            topBar = { AppBar(title = null, navigateUp = navigateUp, scrollBehavior = it) },
        ) { padding ->
            EmptyScreen(MR.strings.details_not_found, modifier = Modifier.padding(padding))
        }
    } else {
        EntryDetailsSkeleton()
    }
}
