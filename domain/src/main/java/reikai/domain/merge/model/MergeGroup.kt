package reikai.domain.merge.model

/**
 * A persisted merge group, as its readers need it. When [overrideSourceRanking] is set the group owns its
 * source ranking; otherwise the global preferred-source list wins.
 */
data class MergeGroup(
    val overrideSourceRanking: Boolean,
)
