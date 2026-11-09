package reikai.presentation.details

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import tachiyomi.core.common.preference.Preference

/**
 * Whether the details screen marks the chapters skip-duplicate folds together: only while "Show duplicate
 * chapters the reader skips" is on and so is [skipDuplicates], the content type's own reader switch, since
 * with that off the reader skips nothing.
 */
internal fun showsSkippedDuplicates(
    show: Preference<Boolean>,
    skipDuplicates: Preference<Boolean>,
    scope: CoroutineScope,
): StateFlow<Boolean> =
    combine(show.changes(), skipDuplicates.changes()) { shown, skipping -> shown && skipping }
        .stateIn(scope, SharingStarted.Eagerly, show.get() && skipDuplicates.get())
