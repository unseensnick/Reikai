package reikai.presentation.library

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.merge.DownloadUnitRow
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.merge.MergedGroupCounts

/**
 * What a library's merge collapse reads besides its rows. [S] is the content type's source key, the
 * type of its preferred-source list. Both libraries build it through [mergeCollapseInputsFlow].
 */
data class MergeCollapseInputs<S>(
    /** Entry id -> group id, for grouped entries only. */
    val membership: Map<Long, Long>,
    val mergingEnabled: Boolean,
    val showSourceIcons: Boolean,
    /** Group id -> member ids in trunk order, only for groups whose source-order override is on. */
    val overrideRankings: Map<Long, List<Long>>,
    /** The global preferred-source list, highest priority first; the fallback ranking. */
    val preferredSources: List<S>,
    /** Per group, the stored stitch's counts. A group absent from the map has not been stitched yet,
     *  which is not the same as having nothing read or nothing left to read. */
    val mergedCounts: Map<Long, MergedGroupCounts>,
    /** Per group, its member chapters, for the download badge to probe. */
    val downloadUnits: Map<Long, List<DownloadUnitRow>>,
)

fun <S> mergeCollapseInputsFlow(
    contentType: ContentType,
    preferredSources: Flow<List<S>>,
    reikaiLibraryPreferences: ReikaiLibraryPreferences,
    mergeGroupRepository: MergeGroupRepository,
    mergedChapterUnitRepository: MergedChapterUnitRepository,
): Flow<MergeCollapseInputs<S>> = combine(
    combine(
        mergeGroupRepository.getAllMembershipsAsFlow(contentType),
        mergeGroupRepository.getOverrideRankingsAsFlow(contentType),
        ::Pair,
    ),
    reikaiLibraryPreferences.seriesMergingEnabled.changes(),
    reikaiLibraryPreferences.showMergeSourceIcons.changes(),
    preferredSources,
    // Folded in rather than read while collapsing: reconciliation writes the stitch while the library is
    // already on screen, and nothing else makes this flow re-emit when it lands.
    combine(
        mergedChapterUnitRepository.getGroupCountsAsFlow(contentType),
        mergedChapterUnitRepository.getDownloadUnitsAsFlow(contentType),
        ::Pair,
    ),
) { (membership, overrideRankings), mergingEnabled, showSourceIcons, preferred, (counts, units) ->
    MergeCollapseInputs(membership, mergingEnabled, showSourceIcons, overrideRankings, preferred, counts, units)
}
