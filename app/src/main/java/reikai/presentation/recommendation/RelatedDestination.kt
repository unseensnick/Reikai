package reikai.presentation.recommendation

import androidx.navigation3.runtime.NavKey
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.recommendation.RelatedMangaCandidate
import reikai.presentation.browse.globalsearch.EntryGlobalSearchRoute
import reikai.presentation.details.detailsRoute

/**
 * Where a tapped related card opens, for the carousel and See all alike. [localId] is the candidate
 * resolved to a stored manga; a tracker-origin card has no installed source to resolve to, so it
 * searches for its title instead.
 */
fun relatedDestination(candidate: RelatedMangaCandidate, localId: Long?): NavKey =
    if (localId != null) {
        EntryId.Manga(localId).detailsRoute()
    } else {
        EntryGlobalSearchRoute(candidate.manga.title, scopedContentType = ContentType.MANGA)
    }
