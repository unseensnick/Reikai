package reikai.domain.merge

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences

/**
 * Emits whenever something the stored stitch is built from changes while a library is open: a group
 * gaining or losing a member, a member leaving or rejoining the library (the stitch counts only the
 * library's), or [contentType]'s own preferred-source list, which picks each group's trunk. Nothing else
 * rewrites the stitch between library updates, so a library reconciles on this rather than on
 * membership alone, which left every merged badge on the old trunk after a Preferred sources edit.
 */
fun stitchInputChanges(
    contentType: ContentType,
    repository: MergeGroupRepository,
    preferences: ReikaiLibraryPreferences,
): Flow<Unit> {
    val preferredSources: Flow<List<Any>> = when (contentType) {
        ContentType.ALL -> error("A stitch belongs to one content type")
        ContentType.MANGA -> preferences.preferredMangaSources.changes()
        ContentType.NOVELS -> preferences.preferredNovelSources.changes()
    }
    return combine(repository.getLibraryMembershipsAsFlow(contentType), preferredSources) { memberships, sources ->
        memberships to sources
    }
        .distinctUntilChanged()
        .map { }
}
