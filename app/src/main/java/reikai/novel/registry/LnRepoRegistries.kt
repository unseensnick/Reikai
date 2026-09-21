package reikai.novel.registry

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import reikai.domain.novel.NovelPreferences

/** Downloads one LN plugin registry; throws when it cannot be read. */
fun interface LnRegistryFetcher {
    suspend fun fetchRepo(repoJsonUrl: String): List<LnRegistryEntry>
}

/** What fetching one added repo gave: its registry, or why it could not be read. */
sealed interface LnRepoResult {
    data class Reached(val entries: List<LnRegistryEntry>) : LnRepoResult

    data class Unreachable(val message: String) : LnRepoResult
}

/**
 * The added LN plugin repos' registries, shared by every screen that lists them. A repo is fetched
 * when first needed and when it is added, and again only on [refresh], so reopening a screen or
 * installing a plugin downloads nothing. Each repo keeps its own outcome, so an unreachable repo is
 * told apart from an empty one. The update check and the post-restore trust check fetch on their
 * own, because both need the registry as it is now.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Inject
@SingleIn(AppScope::class)
class LnRepoRegistries(
    private val fetcher: LnRegistryFetcher,
    private val prefs: NovelPreferences,
) {

    private val mutex = Mutex()

    /** Null until the first load; keyed in the order the added repos are stored. */
    private val loaded = MutableStateFlow<Map<String, LnRepoResult>?>(null)

    val isRefreshing: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Every added repo's outcome, following the added repos. */
    val results: Flow<Map<String, LnRepoResult>> = prefs.addedRepoUrls().changes()
        .onEach { load(it, force = false) }
        .flatMapLatest { loaded.filterNotNull() }

    suspend fun refresh() = load(prefs.addedRepoUrls().get(), force = true)

    /** Adds [repoUrl] only if it reads as a registry, keeping what it fetched so it is not downloaded twice. */
    suspend fun add(repoUrl: String): Result<Unit> = mutex.withLock {
        val entries = try {
            fetcher.fetchRepo(repoUrl)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return@withLock Result.failure(e)
        }
        loaded.value = loaded.value.orEmpty() + (repoUrl to LnRepoResult.Reached(entries))
        prefs.addedRepoUrls().set(prefs.addedRepoUrls().get() + repoUrl)
        Result.success(Unit)
    }

    fun remove(repoUrl: String) {
        prefs.addedRepoUrls().set(prefs.addedRepoUrls().get() - repoUrl)
    }

    private suspend fun load(repos: Set<String>, force: Boolean) = mutex.withLock {
        val kept = if (force) emptyMap() else loaded.value.orEmpty().filterKeys { it in repos }
        val missing = repos.filterNot { it in kept }
        isRefreshing.value = missing.isNotEmpty()
        try {
            val fetched = coroutineScope {
                missing.map { repo -> async { repo to fetch(repo) } }.awaitAll()
            }.toMap()
            loaded.value = repos.associateWith { kept[it] ?: fetched.getValue(it) }
        } finally {
            isRefreshing.value = false
        }
    }

    private suspend fun fetch(repo: String): LnRepoResult {
        return try {
            LnRepoResult.Reached(fetcher.fetchRepo(repo))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LnRepoResult.Unreachable(e.message ?: e::class.simpleName.orEmpty())
        }
    }
}
