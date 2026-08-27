package reikai.presentation.browse.migrate

import androidx.compose.runtime.Immutable
import eu.kanade.domain.source.interactor.SetMigrateSorting
import reikai.domain.source.SourceKey
import tachiyomi.core.common.util.lang.compareToWithCollator

/**
 * One migrate-from source as the shared list sees it, whatever content type it came from.
 *
 * [source] is the provider's own object, carried opaquely so the shared layer never has to know what
 * a manga source or a plugin looks like; only the leaf that picks the icon unwraps it. [isStub] is a
 * source that is gone while its entries remain, which is the case this screen exists for.
 */
@Immutable
data class BrowseMigrateRow(
    val key: SourceKey,
    val name: String,
    val lang: String,
    val count: Long,
    val isStub: Boolean,
    val source: Any,
)

/**
 * Orders the one migrate list, across both content types.
 *
 * A source that is gone leads, in either mode, because it holds entries nothing can open any more.
 * That is upstream's manga rule, and the novel list had none of its own; both now sort here.
 */
fun compareMigrateRows(
    mode: SetMigrateSorting.Mode,
    direction: SetMigrateSorting.Direction,
): Comparator<BrowseMigrateRow> {
    val ascending = Comparator<BrowseMigrateRow> { a, b ->
        when {
            a.isStub != b.isStub -> if (a.isStub) -1 else 1
            mode == SetMigrateSorting.Mode.TOTAL -> a.count.compareTo(b.count)
            // A collator, so an accented name lands where the reader's own language puts it.
            else -> a.name.lowercase().compareToWithCollator(b.name.lowercase())
        }
    }
    return when (direction) {
        SetMigrateSorting.Direction.ASCENDING -> ascending
        SetMigrateSorting.Direction.DESCENDING -> ascending.reversed()
    }
}
