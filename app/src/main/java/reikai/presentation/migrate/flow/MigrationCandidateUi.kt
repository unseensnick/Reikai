package reikai.presentation.migrate.flow

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import reikai.domain.novel.model.Novel
import reikai.presentation.browse.EntryBrowseItemUi
import reikai.presentation.browse.toEntryBrowseUi
import reikai.presentation.novel.browse.NovelBrowseScreen
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.domain.manga.model.Manga

/** Per-type mapping into the shared browse cell; the one place the flow UI branches on a handle. */
internal fun MigrationCandidate.toBrowseUi(): EntryBrowseItemUi = when (val h = handle) {
    is Manga -> h.toEntryBrowseUi()
    is NovelCandidateHandle -> h.item.toEntryBrowseUi(inLibrary = false, site = h.site)
    else -> error("unknown migration candidate handle")
}

/** A candidate's stable lazy-list key: the url/path identity from the handle, unique per source,
 *  where title is not (same-titled editions on one source would duplicate a title-based key). */
internal val MigrationCandidate.stableKey: String
    get() = "$sourceKey:${
        when (val h = handle) {
            is Manga -> h.url
            is NovelCandidateHandle -> h.item.path
            else -> title
        }
    }"

/** Open a candidate's details page (the long-press verify affordance). */
internal fun MigrationCandidate.openDetails(navigator: Navigator) {
    when (val h = handle) {
        is Manga -> navigator.push(MangaScreen(h.id))
        is NovelCandidateHandle -> navigator.push(NovelScreen(sourceKey, h.item.path))
    }
}

/** Open a favorites-picker row's details page (the cover-tap affordance). */
internal fun MigrationFavorite.openDetails(navigator: Navigator) {
    when (val p = payload) {
        is Manga -> navigator.push(MangaScreen(p.id))
        is Novel -> navigator.push(NovelScreen(p.source, p.url))
    }
}

/** Open a flow entry's details page (the migrate host's Show affordance). */
internal fun MigrationEntry.openDetails(navigator: Navigator) {
    when (val p = payload) {
        is Manga -> navigator.push(MangaScreen(p.id))
        is Novel -> navigator.push(NovelScreen(p.source, p.url))
    }
}

/** Push the deep target picker: a full browse of one source (filters, pagination), which hands the
 *  pick back to the migration list, or opens the shared dialog when reached from the single-entry
 *  search screen. Per-type because the two browse stacks are different screens. False when the
 *  picker cannot open (a malformed source key); callers toast instead of silently doing nothing. */
internal fun openDeepPicker(navigator: Navigator, entry: MigrationEntry, sourceKey: String, query: String): Boolean {
    when (val p = entry.payload) {
        is Manga -> {
            val sourceId = sourceKey.toLongOrNull() ?: return false
            navigator.push(MigrationDeepSearchScreen(p.id, sourceId, query))
        }
        is Novel -> navigator.push(NovelBrowseScreen(sourceKey, query, migratePickFor = p.id))
        else -> return false
    }
    return true
}

/** Land on a committed target's details. On a replace the origin's now-stale details screen (the one
 *  the single-entry routes sit on top of) is swapped out instead of left underneath; a copy keeps it
 *  (the origin is still favorited). Call after popping the search/picker screens themselves. */
internal fun MigrationCandidate.openDetailsAfterCommit(navigator: Navigator, replaced: Boolean) {
    val details: Screen = when (val h = handle) {
        is Manga -> MangaScreen(h.id)
        is NovelCandidateHandle -> NovelScreen(sourceKey, h.item.path)
        else -> return
    }
    val lastIsDetails = navigator.lastItem is MangaScreen || navigator.lastItem is NovelScreen
    if (replaced && lastIsDetails) navigator.replace(details) else navigator.push(details)
}
