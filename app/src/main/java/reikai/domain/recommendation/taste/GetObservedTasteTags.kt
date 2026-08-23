package reikai.domain.recommendation.taste

import dev.zacsweers.metro.Inject

data class ObservedTag(val tag: String, val count: Int)

/**
 * The tags the cached tracker libraries actually contain, most common first. Feeds the adult-tag
 * pickers so a user chooses a real tag rather than typing one: tags are matched exactly, so a typo
 * or a term nothing uses would be a setting that silently does nothing.
 */
@Inject
class GetObservedTasteTags(
    private val repository: TasteLibraryRepository,
) {
    suspend fun await(): List<ObservedTag> =
        repository.getAll()
            .flatMap { it.tags }
            .groupingBy { it }
            .eachCount()
            .map { (tag, count) -> ObservedTag(tag, count) }
            .sortedWith(compareByDescending<ObservedTag> { it.count }.thenBy { it.tag })
}
