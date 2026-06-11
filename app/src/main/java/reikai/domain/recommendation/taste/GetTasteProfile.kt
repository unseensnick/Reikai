package reikai.domain.recommendation.taste

/**
 * Computes the current [TasteProfile] from the locally cached tracker libraries. Pure read + reduce,
 * no network: the pull ([RefreshTrackerLibrary]) keeps the cache current out of band. Returns
 * [TasteProfile.EMPTY] when nothing has been pulled yet, so the carousel degrades to popularity order.
 */
class GetTasteProfile(
    private val repository: TasteLibraryRepository,
    private val compute: ComputeTasteProfile,
) {
    suspend fun await(): TasteProfile = compute(repository.getAll())
}
