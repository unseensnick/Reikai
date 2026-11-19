package reikai.presentation.migrate.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import androidx.navigation3.runtime.result.ResultEventBus
import reikai.domain.entry.EntryId
import java.io.Serializable

/** A target picked on a pushed browse screen for [entry], as the stored row [targetRawId]. */
data class MigrationPick(val entry: EntryId, val targetRawId: Long)

/**
 * What a catalogue opened as a migration picker answers: the entry being migrated, and the flow that
 * asked. [flowId] is a field of the asking screen, saved with it, so a pick made after process death
 * still reaches the restored screen, and a pick from another flow lands on a key nobody collects.
 */
data class MigrationPickFor(val entry: EntryId, val flowId: Long) : Serializable

private fun migrationPickKey(flowId: Long) = "migrationPick:$flowId"

fun ResultEventBus.sendMigrationPick(request: MigrationPickFor, targetRawId: Long) =
    sendResult(migrationPickKey(request.flowId), MigrationPick(request.entry, targetRawId))

/**
 * Receives the picks made for flow [flowId], in the asking screen only, so each key has one collector.
 * The key's buffered picks are dropped once [owner] is cleared, which happens as the screen leaves the
 * stack, so nothing waits on a key no screen will read again.
 */
@Composable
fun MigrationPickEffect(flowId: Long, owner: ViewModel, onPick: (MigrationPick) -> Unit) {
    val bus = LocalResultEventBus.current
    val key = migrationPickKey(flowId)
    ResultEffect<MigrationPick>(key, bus) { onPick(it) }
    remember(owner, key) {
        // Keyed, and added once: adding under a key that has one closes the old one at once.
        if (owner.getCloseable<AutoCloseable>(key) == null) owner.addCloseable(key) { bus.removeResult(key) }
    }
}

/**
 * Why a manually picked target could not be applied, consumed once by the screen that shows it. A pick
 * is taken as it arrives, so one that fails has nowhere to go, and without this the screen stayed as it
 * was with nothing said to the user. Every screen that takes a pick, from a result strip or from the
 * pushed browse screen, can fail to apply it the same ways.
 */
sealed interface PickOutcome {
    /** The picked row could not be read back (deleted, or its source went away). */
    data object Unavailable : PickOutcome

    /** The entry was picked as its own target; migrating onto itself would do nothing. */
    data object SameEntry : PickOutcome

    /** The target came back with no chapters, so there is nothing to migrate onto. */
    data object NoChapters : PickOutcome
}
