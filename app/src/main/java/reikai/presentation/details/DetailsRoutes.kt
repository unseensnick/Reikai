package reikai.presentation.details

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.MangaRoute
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import reikai.novel.host.NovelItem
import reikai.presentation.browse.catalogue.EntryBrowseRow
import reikai.presentation.browse.item
import reikai.presentation.browse.manga
import reikai.presentation.novel.browse.NovelLibraryAdder
import reikai.presentation.novel.details.NovelRoute
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.i18n.MR

// The one place a series' details page is built, for both types, so a change to how a page is addressed
// lands here rather than at each caller. DetailsRoutesGuardTest fails on a construction anywhere else.
// A page is addressed by its row id, so a listing that has no row yet stores one before it opens.

/** The details page of [this] entry; one since removed opens on a not-found page. */
fun EntryId.detailsRoute(fromSource: Boolean = false): NavKey = when (this) {
    is EntryId.Manga -> MangaRoute(rawId, fromSource)
    is EntryId.Novel -> NovelRoute(rawId, fromSource)
}

/**
 * The details page a browse result of the source at [sourceKey] opens, which is always from that source.
 * A manga result is stored already; a novel's row is stored first, outside the library. Null when it
 * cannot be.
 */
suspend fun EntryBrowseRow.detailsRoute(sourceKey: SourceKey, novels: NovelLibraryAdder): NavKey? =
    when (sourceKey) {
        is SourceKey.Manga -> EntryId.Manga(manga.id).detailsRoute(fromSource = true)
        is SourceKey.Novel -> item.storedDetailsRoute(sourceKey.id, novels)
    }

/**
 * The details page of this listed novel on [sourceId], opened from that source once its row is stored; null
 * when it cannot be.
 */
suspend fun NovelItem.storedDetailsRoute(
    sourceId: String,
    novels: NovelLibraryAdder,
): NavKey? = runCatchingCancellable { withIOContext { novels.materialize(this@storedDetailsRoute, sourceId) } }
    .onFailure { logcat(LogPriority.ERROR, it) { "Could not store a listed novel to open it" } }
    .getOrNull()
    ?.let { EntryId.Novel(it.id).detailsRoute(fromSource = true) }

/** Whether this route is [entry]'s details page. */
fun NavKey.shows(entry: EntryId): Boolean = when (entry) {
    is EntryId.Manga -> (this as? MangaRoute)?.mangaId == entry.rawId
    is EntryId.Novel -> (this as? NovelRoute)?.novelId == entry.rawId
}

/**
 * Opens the details page a tap asks for, one at a time: a second tap while a novel's row is still being
 * stored opens nothing, since its page would land over whatever the first one opened. A page that cannot be
 * built says so.
 */
class DetailsOpener(
    private val scope: CoroutineScope,
    private val push: (NavKey) -> Unit,
    // Read at the tap, so drawing a screen that holds an opener never reaches the graph.
    private val novels: () -> NovelLibraryAdder,
    private val onFailure: () -> Unit,
) {
    private var pending: Job? = null

    fun open(row: EntryBrowseRow, sourceKey: SourceKey) = open { row.detailsRoute(sourceKey, it) }

    /**
     * Opens the page [screen] builds through [navigate], a push unless the caller opens it another way. One
     * that cannot be built is reported, then [afterFailure] runs, for a caller that waited on it.
     */
    fun open(
        navigate: (NavKey) -> Unit = push,
        afterFailure: () -> Unit = {},
        screen: suspend (NovelLibraryAdder) -> NavKey?,
    ) {
        if (pending?.isActive == true) return
        pending = scope.launch {
            screen(novels())?.let(navigate) ?: run {
                onFailure()
                afterFailure()
            }
        }
    }
}

/** The [DetailsOpener] every tap on a result or a candidate opens through. */
@Composable
fun rememberDetailsOpener(): DetailsOpener {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    return remember(navigator, scope) {
        DetailsOpener(scope, navigator::push, { context.appGraph.novelLibraryAdder }) {
            context.toast(MR.strings.details_open_failed)
        }
    }
}
