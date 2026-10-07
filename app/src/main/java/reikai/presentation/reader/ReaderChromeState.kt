package reikai.presentation.reader

import androidx.compose.runtime.Immutable
import reikai.domain.entry.EntryId

/**
 * What the reader's chrome says about the entry being read, in words rather than in either engine's
 * model. The titles are nullable because both are unknown until the entry and its chapter load, and
 * the chrome renders before that. [entry] is the entry the titles belong to, which the engine names
 * by its Edit info title.
 */
@Immutable
data class ReaderChromeState(
    val entryTitle: String? = null,
    val chapterTitle: String? = null,
    val entry: EntryId? = null,
)
