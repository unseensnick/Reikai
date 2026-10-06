package reikai.presentation.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import reikai.domain.library.ContentType
import reikai.domain.library.includes
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.PullRefresh
import kotlin.time.Duration.Companion.seconds

/**
 * What a started (or refused) library update tells the user, on every surface that starts one.
 * [category] is true when the update covers one category rather than the whole library.
 */
fun libraryRefreshMessage(started: Boolean, chip: ContentType, category: Boolean): StringResource = when {
    !started -> MR.strings.update_already_running
    category -> MR.strings.updating_category
    chip == ContentType.ALL -> MR.strings.updating_both_libraries
    else -> MR.strings.updating_library
}

/**
 * Whether a library update behind [chip] is running: [updating] pairs each provider's content type
 * with its update job's running flow, and a type the chip hides never counts.
 */
fun chipUpdating(chip: Flow<ContentType>, updating: List<Pair<ContentType, Flow<Boolean>>>): Flow<Boolean> =
    combine(chip, combine(updating.map { it.second }) { it.toList() }) { shown, running ->
        updating.indices.any { shown.includes(updating[it].first) && running[it] }
    }

/**
 * Pull-to-refresh over a library update job, whose spinner follows [updating]. Material reads the flag
 * on the release frame, before the job reports running, and a short job may never be seen running, so
 * the spinner is also held from the pull until the job is seen running or a second passes.
 */
@Composable
fun LibraryUpdatePullRefresh(
    updating: Boolean,
    enabled: Boolean,
    onRefresh: () -> Boolean,
    indicatorPadding: PaddingValues = PaddingValues(0.dp),
    content: @Composable () -> Unit,
) {
    var pulled by remember { mutableStateOf(false) }
    val running by rememberUpdatedState(updating)
    LaunchedEffect(pulled) {
        if (!pulled) return@LaunchedEffect
        withTimeoutOrNull(1.seconds) { snapshotFlow { running }.first { it } }
        pulled = false
    }
    PullRefresh(
        refreshing = pulled || updating,
        enabled = enabled,
        onRefresh = { pulled = onRefresh() },
        indicatorPadding = indicatorPadding,
        content = content,
    )
}
