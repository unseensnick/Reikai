package reikai.domain.recommendation.taste

import dev.zacsweers.metro.Inject

/**
 * Pure read and reduce, no network: [RefreshTrackerLibrary] keeps the cache current out of band.
 * Returns [TasteProfile.EMPTY] when nothing has been pulled, so the carousel degrades to popularity
 * order rather than failing.
 */
@Inject
class GetTasteProfile(
    private val repository: TasteLibraryRepository,
    private val compute: ComputeTasteProfile,
) {
    suspend fun await(): TasteProfile = compute(repository.getAll())
}
