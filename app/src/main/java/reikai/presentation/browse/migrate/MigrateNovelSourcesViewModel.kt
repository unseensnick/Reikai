package reikai.presentation.browse.migrate

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.identity
import reikai.domain.novel.resolveSourceIdentity
import reikai.novel.local.LocalNovelSource
import reikai.novel.source.NovelExtensionFormat
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.system.logcat
import kotlin.time.Duration.Companion.seconds

/**
 * Novel side of the Browse migration source list: favorited novels grouped by their source, with a
 * count per source. A source's display name and icon resolve through a 3-step chain so a row shows
 * even when its plugin is uninstalled (manga stub parity): the live installed source, else the
 * last-known [NovelPreferences.seenNovelSources] cache, else the raw plugin id.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class MigrateNovelSourcesViewModel(
    private val novelRepository: NovelRepository,
    private val sourceManager: NovelSourceManager,
    private val novelPreferences: NovelPreferences,
) : ViewModel() {

    private val failures = Channel<Unit>(Channel.UNLIMITED)

    /** One emission per failed favourites read, shown as the same error the manga half shows. */
    val failed: Flow<Unit> = failures.receiveAsFlow()

    /** Sources holding favourites, unsorted: the shared migrate list orders both types at once. */
    val sources: StateFlow<List<NovelMigrateSource>?> = combine(
        novelRepository.getSourcesWithLibraryNovelAsFlow(),
        sourceManager.loadedSources(),
        novelPreferences.seenNovelSources().changes(),
    ) { counts, installedSources, cached ->
        val installed = installedSources.associate { it.id to it.identity() }
        val formats = installedSources.associate { it.id to it.format }
        buildNovelMigrateSources(
            countsPerSource = counts,
            installed = installed,
            cached = cached,
        ).map { it.copy(format = formats[it.id]) }
    }
        .catch {
            logcat(LogPriority.ERROR, it)
            failures.send(Unit)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)
}

/** One migrate-from row: a novel source with how many favorited novels it holds. [isInstalled] is
 *  false for a stub (plugin uninstalled or never seen), which still migrates from its stored data. */
@Immutable
data class NovelMigrateSource(
    val id: String,
    val name: String,
    val iconUrl: String?,
    val lang: String,
    val count: Long,
    val isInstalled: Boolean,
    /** How the source is packaged; null for one not installed, which names none. */
    val format: NovelExtensionFormat? = null,
)

/**
 * Pure core: turn (source id, favorited novel count) pairs into per-source rows, resolving each
 * id's display identity as installed -> cached -> raw id. [installed] are the currently registered
 * sources; [cached] the last-known identities that survive an uninstall. A row with neither falls
 * back to its plugin id as the name and a null icon (the row's icon slot renders a book placeholder).
 */
internal fun buildNovelMigrateSources(
    countsPerSource: List<Pair<String, Long>>,
    installed: Map<String, LnSourceIdentity>,
    cached: Map<String, LnSourceIdentity>,
): List<NovelMigrateSource> {
    // A local series is the user's own files, which upstream's manga list leaves out too.
    return countsPerSource.filterNot { (id, _) -> LocalNovelSource.isLocal(id) }.map { (id, count) ->
        val identity = resolveSourceIdentity(id, installed[id], cached[id])
        NovelMigrateSource(
            id = id,
            name = identity.name,
            iconUrl = identity.iconUrl,
            lang = identity.lang.orEmpty(),
            count = count,
            isInstalled = id in installed,
        )
    }
}
