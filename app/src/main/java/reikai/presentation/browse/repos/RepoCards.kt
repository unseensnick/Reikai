package reikai.presentation.browse.repos

import mihon.domain.extension.model.ExtensionStore
import reikai.domain.extension.RepoStatus
import reikai.domain.extension.hasSigningKey
import reikai.domain.extension.repoNameFromAddress
import reikai.domain.library.ContentType

enum class RepoFormat { STORE, PLUGINS }

/** One card on the Repos screen: an extension store or an LN plugin repo, and how its last fetch went. */
data class RepoCardUi(
    val address: String,
    val format: RepoFormat,
    val name: String,
    val website: String?,
    val discord: String?,
    val signingKey: String?,
    val status: RepoStatus,
) {
    /** What the repo serves, as far as its last fetch showed; a plugin repo only ever serves novels. */
    val contentTypes: List<ContentType>
        get() = when {
            format == RepoFormat.PLUGINS -> listOf(ContentType.NOVELS)
            status is RepoStatus.Reached -> buildList {
                if (status.manga > 0) add(ContentType.MANGA)
                if (status.novels > 0) add(ContentType.NOVELS)
            }
            else -> emptyList()
        }
}

/** A card names its content type only when the list holds both, as the download queue's cards do. */
fun showTypeBadges(cards: List<RepoCardUi>): Boolean = cards.flatMap { it.contentTypes }.distinct().size > 1

/** A repo not fetched yet, or added since the last fetch, reads as still checking. */
fun repoCards(
    stores: List<ExtensionStore>,
    storeStatuses: Map<String, RepoStatus>?,
    pluginRepos: Set<String>,
    pluginStatuses: Map<String, RepoStatus>?,
): List<RepoCardUi> {
    val storeCards = stores.map { store ->
        RepoCardUi(
            address = store.indexUrl,
            format = RepoFormat.STORE,
            name = store.name,
            website = store.contact.website,
            discord = store.contact.discord,
            signingKey = store.signingKey.takeIf { store.hasSigningKey },
            status = storeStatuses?.get(store.indexUrl) ?: RepoStatus.Checking,
        )
    }
    val pluginCards = pluginRepos.map { url ->
        val (name, website) = repoNameFromAddress(url)
        RepoCardUi(
            address = url,
            format = RepoFormat.PLUGINS,
            name = name,
            website = website,
            discord = null,
            signingKey = null,
            status = pluginStatuses?.get(url) ?: RepoStatus.Checking,
        )
    }
    return (storeCards + pluginCards).sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
}

/**
 * Adds an address as whichever kind of repo it reads as. The plugin repo is tried first because that
 * fetch reads the whole body, which the HTTP cache then serves to the store attempt; the other way
 * round, the store attempt stops at the first byte, so nothing is cached and the address downloads twice.
 */
suspend fun addRepoOfEitherKind(
    addPluginRepo: suspend () -> Result<Unit>,
    addStore: suspend () -> Result<Unit>,
): Boolean = addPluginRepo().isSuccess || addStore().isSuccess
