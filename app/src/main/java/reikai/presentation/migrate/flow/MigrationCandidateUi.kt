package reikai.presentation.migrate.flow

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import reikai.presentation.migrate.PickMember
import reikai.presentation.novel.details.NovelScreen

/**
 * Where the flow opens an entry's details page.
 *
 * This is the one place the shared flow branches on what an entry actually is, because a details
 * screen is a per-type Voyager screen and there is no neutral one to push. Everything else about a
 * candidate travels through [MigrationFlowAdapter] as neutral data.
 */
internal fun MigrationEntry.openDetails(navigator: Navigator) = navigator.pushDetails(payload)

/** The details page of a row in the entry picker, for checking an entry before selecting it. */
internal fun MigrationFavorite.openDetails(navigator: Navigator) = navigator.pushDetails(payload)

/** The details page of a merged source, for checking which one it is before migrating it. */
internal fun PickMember.openDetails(navigator: Navigator) = navigator.pushDetails(payload)

private fun Navigator.pushDetails(payload: MigrationPayload) {
    when (payload) {
        is MigrationPayload.OfManga -> push(MangaScreen(payload.manga.id))
        is MigrationPayload.OfNovel -> push(NovelScreen(payload.novel.source, payload.novel.url))
    }
}

/** The details page of a candidate, for checking a match before committing to it. */
internal fun MigrationCandidate.openDetails(navigator: Navigator) {
    when (val handle = handle) {
        is MangaCandidateHandle -> navigator.push(MangaScreen(handle.manga.id, true))
        is NovelCandidateHandle -> navigator.push(
            NovelScreen(sourceKey, handle.item.path, handle.item.cover, fromSource = true),
        )
    }
}

/**
 * Land on the target after migrating onto it.
 *
 * A replace leaves the entry migrated away behind on the stack, showing a page that no longer
 * describes anything in the library, so the target replaces it; a copy keeps it. The check is by
 * identity, not type: a migration launched from a merged entry's details can migrate a member that
 * is NOT the page below, and replacing that page would discard a live details screen.
 */
internal fun MigrationCandidate.openDetailsAfterCommit(
    navigator: Navigator,
    replaced: Boolean,
    migrated: MigrationEntry,
) {
    val (details: Screen, previousIsMigrated: Boolean) = when (val handle = handle) {
        is MangaCandidateHandle -> MangaScreen(handle.manga.id) to
            ((navigator.lastItem as? MangaScreen)?.mangaId == migrated.id.rawId)
        is NovelCandidateHandle -> {
            val last = navigator.lastItem as? NovelScreen
            NovelScreen(sourceKey, handle.item.path, handle.item.cover) to (
                handle.stored != null &&
                    last != null &&
                    migrated.isOwnListing(last.sourceId, last.novelUrl)
                )
        }
    }
    if (replaced && previousIsMigrated) navigator.replace(details) else navigator.push(details)
}
