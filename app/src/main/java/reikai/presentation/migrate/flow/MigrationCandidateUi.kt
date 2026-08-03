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
internal fun MigrationEntry.openDetails(navigator: Navigator) {
    when (val payload = payload) {
        is Manga -> navigator.push(MangaScreen(payload.id))
        is Novel -> navigator.push(NovelScreen(payload.source, payload.url))
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
 * entries are still there. Only a details page of the same content type can be the one being
 * replaced, so the check is type-matched.
 */
internal fun MigrationCandidate.openDetailsAfterCommit(navigator: Navigator, replaced: Boolean) {
    val (details: Screen, previousIsDetails: Boolean) = when (val handle = handle) {
        is Manga -> MangaScreen(handle.id) to (navigator.lastItem is MangaScreen)
        is NovelCandidateHandle -> {
            val stored = handle.stored
            NovelScreen(sourceKey, handle.item.path) to (stored != null && navigator.lastItem is NovelScreen)
        }
        else -> return
    }
    if (replaced && previousIsDetails) navigator.replace(details) else navigator.push(details)
}
