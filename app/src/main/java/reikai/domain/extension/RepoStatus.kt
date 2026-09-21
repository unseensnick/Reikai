package reikai.domain.extension

import eu.kanade.tachiyomi.extension.model.Extension
import reikai.novel.registry.LnRepoResult

/**
 * What the last fetch of one repo gave, for an extension store and an LN plugin repo alike. The
 * counts come from that repo's own listing, so an extension two stores both list counts for each.
 */
sealed interface RepoStatus {
    data object Checking : RepoStatus

    data class Reached(val manga: Int, val novels: Int) : RepoStatus

    data class Unreachable(val message: String) : RepoStatus
}

fun Result<List<Extension.Available>>.toRepoStatus(): RepoStatus = fold(
    onSuccess = { listing ->
        val novels = listing.count { it.kind != Extension.Kind.MANGA }
        RepoStatus.Reached(manga = listing.size - novels, novels = novels)
    },
    onFailure = { RepoStatus.Unreachable(it.message ?: it::class.simpleName.orEmpty()) },
)

/** Every LN plugin is a novel source. */
fun LnRepoResult.toRepoStatus(): RepoStatus = when (this) {
    is LnRepoResult.Reached -> RepoStatus.Reached(manga = 0, novels = entries.size)
    is LnRepoResult.Unreachable -> RepoStatus.Unreachable(message)
}
