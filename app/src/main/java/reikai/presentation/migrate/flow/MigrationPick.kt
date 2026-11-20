package reikai.presentation.migrate.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModel
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import androidx.navigation3.runtime.result.ResultEventBus
import kotlinx.serialization.Serializable
import mihon.core.navigation.LocalNavigator
import reikai.domain.entry.EntryId
import reikai.presentation.browse.catalogue.EntryCatalogueRoute

/** A target picked on a pushed browse screen for [current], as the stored row [target]; upstream's event, typed. */
data class MatchOverrideEvent(val current: EntryId, val target: Long)

/**
 * What a catalogue opened as a migration picker answers: the entry being migrated, and the flow that
 * asked. [flowId] is a field of the asking screen, saved with it, so a pick made after process death
 * still reaches the restored screen, and a pick from another flow lands on a key nobody collects.
 */
@Serializable
data class MigrationPickFor(val entry: EntryId, val flowId: Long)

private fun migrationPickKey(flowId: Long) = "migrationPick:$flowId"

fun ResultEventBus.sendMigrationPick(request: MigrationPickFor, targetRawId: Long) =
    sendResult(migrationPickKey(request.flowId), MatchOverrideEvent(request.entry, targetRawId))

/**
 * Sends a target picked on the catalogue [migrateFor] opened to the screen that asked, then pops back to it, as
 * upstream's migration search does. Only while a catalogue picking for [migrateFor] is on top: a second tap, or a
 * novel pick landing as it leaves, would otherwise pop the asking screen too, past its exit guard. Read off the
 * route on top at the tap, since a back gesture over the catalogue draws it before the page above has popped.
 */
@Composable
fun rememberMigrationPickReport(migrateFor: MigrationPickFor?): (target: Long) -> Unit {
    val navigator = LocalNavigator.current
    val bus = LocalResultEventBus.current
    return remember(navigator, bus, migrateFor) {
        val report: (Long) -> Unit = report@{ target ->
            if (migrateFor == null || (navigator.current as? EntryCatalogueRoute)?.migrateFor != migrateFor) {
                return@report
            }
            bus.sendMigrationPick(migrateFor, target)
            navigator.popTo { it is MigrationFlowRoute }
        }
        report
    }
}

/**
 * Receives the picks made for flow [flowId], in the asking screen only, so each key has one collector, once
 * [ready]: a pick waits on the bus until then. The key's buffered picks are dropped once [owner] is cleared,
 * which happens as the screen leaves the stack, so nothing waits on a key no screen will read again. Called
 * before the screen's loading return, so the cleanup is registered however soon the screen leaves.
 */
@Composable
fun MigrationPickEffect(flowId: Long, owner: ViewModel, ready: Boolean, onPick: (MatchOverrideEvent) -> Unit) {
    val bus = LocalResultEventBus.current
    val key = migrationPickKey(flowId)
    LaunchedEffect(owner, key) {
        // Keyed, and added once: adding under a key that has one closes the old one at once.
        if (owner.getCloseable<AutoCloseable>(key) == null) owner.addCloseable(key) { bus.removeResult(key) }
    }
    if (ready) ResultEffect<MatchOverrideEvent>(key, bus) { onPick(it) }
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
