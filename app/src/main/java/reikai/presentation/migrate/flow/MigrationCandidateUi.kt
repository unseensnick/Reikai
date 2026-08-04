package reikai.presentation.migrate.flow

import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import reikai.domain.novel.model.Novel
import reikai.presentation.novel.details.NovelScreen
import tachiyomi.domain.manga.model.Manga

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

private fun Navigator.pushDetails(payload: Any) {
    when (payload) {
        is Manga -> push(MangaScreen(payload.id))
        is Novel -> push(NovelScreen(payload.source, payload.url))
    }
}

/** The details page of a candidate, for checking a match before committing to it. */
internal fun MigrationCandidate.openDetails(navigator: Navigator) {
    when (val handle = handle) {
        is Manga -> navigator.push(MangaScreen(handle.id, true))
        is NovelCandidateHandle -> navigator.push(NovelScreen(sourceKey, handle.item.path))
    }
}

/**
 * Land on the target after migrating onto it.
 *
 * A replace leaves the entry that was migrated away behind on the stack, showing a page that no
 * longer describes anything in the library, so the target replaces it. A copy keeps it, since both
 * entries are still there. The check is by identity, not just type: a migration launched from a
 * merged entry's details can migrate a member that is NOT the page below, and replacing that page
 * would discard a details screen that still describes a live entry.
 */
internal fun MigrationCandidate.openDetailsAfterCommit(
    navigator: Navigator,
    replaced: Boolean,
    migrated: MigrationEntry,
) {
    val (details: Screen, previousIsMigrated: Boolean) = when (val handle = handle) {
        is Manga -> MangaScreen(handle.id) to
            ((navigator.lastItem as? MangaScreen)?.mangaId == migrated.id.rawId)
        is NovelCandidateHandle -> {
            val last = navigator.lastItem as? NovelScreen
            val migratedNovel = migrated.payload as? Novel
            NovelScreen(sourceKey, handle.item.path) to (
                handle.stored != null &&
                    last != null &&
                    migratedNovel != null &&
                    last.sourceId == migratedNovel.source &&
                    last.novelUrl == migratedNovel.url
                )
        }
        else -> return
    }
    if (replaced && previousIsMigrated) navigator.replace(details) else navigator.push(details)
}
