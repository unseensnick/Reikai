// Keeps upstream's filename after the collapse below, so the path still matches refs/mihon and a
// sync diffs it in place. Renaming to match the one remaining class would hide it from that diff.
@file:Suppress("ktlint:standard:filename")

package eu.kanade.presentation.history

import tachiyomi.domain.history.model.HistoryWithRelations
import java.time.LocalDate

// RK: the screen body and its previews moved to the shared reikai.presentation.history
// .ReikaiHistoryScreen (manga + novel history render through it now). Only the ui model stays,
// because Mihon's HistoryViewModel still emits it and the shared screen consumes it.

sealed interface HistoryUiModel {
    data class Header(val date: LocalDate) : HistoryUiModel
    data class Item(val item: HistoryWithRelations) : HistoryUiModel
}
