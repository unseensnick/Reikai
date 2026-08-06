package reikai.presentation.migrate.flow

import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import reikai.domain.entry.EntryId
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

// Searching the chosen target sources, shared by the batch list's override picker and the
// single-entry search screen. The two ran their own copies of all of this, which is how they came to
// disagree about what an errored source means.

/**
 * What one source has to say. Sealed rather than a candidate list beside a nullable error and a
 * loading flag: of those eight combinations only three mean anything, and the rest read as "this
 * source has nothing", which is a different and wrong answer.
 */
sealed interface StripResult {
    data object Loading : StripResult

    data class Loaded(val candidates: List<MigrationCandidate>) : StripResult

    data class Failed(val error: String) : StripResult
}

/** The candidates this source returned, empty while loading or on failure. */
val StripResult.candidates: List<MigrationCandidate>
    get() = (this as? StripResult.Loaded)?.candidates.orEmpty()

/**
 * A source worth showing under the has-results filter. A failure is kept: the user needs to tell a
 * source that could not answer from one that answered "nothing", and hiding it strands the retry.
 */
val StripResult.hasSomethingToSay: Boolean
    get() = this !is StripResult.Loaded || candidates.isNotEmpty()

/**
 * The highest chapter number in [this], or null when there is none to show.
 *
 * A negative number is the recognizer saying it could not read one off the chapter, so it is not a
 * count the user should be shown or compared against. Both adapters state that rule at eight call
 * sites between them, which is eight places to miss it.
 */
inline fun <T> List<T>.latestChapterNumber(number: (T) -> Double): Double? =
    maxOfOrNull(number)?.takeIf { it >= 0.0 }

/**
 * The chosen target sources, resolved against what is enabled. Saved selection, else pinned, else
 * everything enabled: the same three tiers the config screen seeds from, so what it showed is what
 * gets searched.
 */
fun MigrationFlowAdapter.sourcesFor(): List<MigrationSourceUi> {
    val enabled = enabledSources()
    val byKey = enabled.associateBy { it.key }
    return savedSelection().mapNotNull { byKey[it] }.ifEmpty {
        val pinned = pinnedKeys()
        enabled.filter { it.key in pinned }.ifEmpty { enabled }
    }
}

/**
 * The user's [query] plus the run's extra query, which the config screen asks for and the flow
 * carries as a screen argument. Only the manual searches need this: [MigrationFlowAdapter.suggest]
 * takes the whole tuning and its engines fold the extra query in themselves.
 */
fun String.withExtraQuery(extraQuery: String?): String = listOfNotNull(
    trim().takeIf(String::isNotBlank),
    extraQuery?.trim()?.takeIf(String::isNotBlank),
).joinToString(" ")

/**
 * Search every source for [query] at once, reporting each as it lands.
 *
 * [isCurrent] is re-checked after the search returns because cancelling cannot stop a coroutine that
 * is already past its last suspension point, so a superseded search could otherwise write over the
 * one that replaced it. Callers publish their placeholder strips BEFORE calling this: waiting for
 * every source meant one dead source hid the rest.
 */
suspend fun MigrationFlowAdapter.fanOutCandidates(
    entry: MigrationEntry,
    query: String,
    sources: List<MigrationSourceUi>,
    permits: Semaphore,
    isCurrent: () -> Boolean,
    onResult: (sourceKey: String, result: StripResult) -> Unit,
): Unit = coroutineScope {
    sources.forEach { source ->
        launch {
            val result = permits.withPermit {
                runCatchingCancellable { candidates(entry, query, source.key) }
            }
            if (!isCurrent()) return@launch
            onResult(
                source.key,
                result.fold(
                    onSuccess = { StripResult.Loaded(it) },
                    onFailure = { StripResult.Failed(it.message ?: it.javaClass.simpleName) },
                ),
            )
        }
    }
}

/** A target picked on a pushed browse screen, once it has been read back. */
sealed interface PendingPick {
    data class Ready(val candidate: MigrationCandidate) : PendingPick

    data class Rejected(val outcome: PickOutcome) : PendingPick
}

/**
 * Take the deep pick waiting for [entryId] and read it back into a candidate, or say why it cannot
 * be applied. Null when there is no pick for this entry.
 *
 * Both screens that collect a pick can fail the same two ways, and the handoff clears on read, so a
 * failure that returns quietly leaves the user staring at an unchanged screen.
 */
suspend fun MigrationFlowAdapter.takePendingPick(
    handoff: MigrationPickHandoff,
    entryId: EntryId,
): PendingPick? {
    val targetRawId = handoff.take(entryId) ?: return null
    // An entry is never its own target: the engines would no-op and the row would read as migrated
    // with nothing done.
    if (targetRawId == entryId.rawId) return PendingPick.Rejected(PickOutcome.SameEntry)
    val candidate = runCatchingCancellable { storedCandidate(targetRawId) }.getOrNull()
        ?: return PendingPick.Rejected(PickOutcome.Unavailable)
    return PendingPick.Ready(candidate)
}

/** Announce a pick that could not be applied, then consume it. */
@Composable
fun PickOutcomeToast(outcome: PickOutcome?, onConsumed: () -> Unit) {
    val context = LocalContext.current
    val unavailable = stringResource(MR.strings.migrationFlow_pickUnavailable)
    val sameEntry = stringResource(MR.strings.migrationFlow_pickSameEntry)
    val noChapters = stringResource(MR.strings.migrationListScreen_matchWithoutChapterToast)
    LaunchedEffect(outcome) {
        when (outcome) {
            PickOutcome.Unavailable -> context.toast(unavailable)
            PickOutcome.SameEntry -> context.toast(sameEntry)
            PickOutcome.NoChapters -> context.toast(noChapters, Toast.LENGTH_LONG)
            null -> return@LaunchedEffect
        }
        onConsumed()
    }
}
