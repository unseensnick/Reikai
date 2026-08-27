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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.novel.source.NovelSourceManager
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

    /** Sources holding favourites, unsorted: the shared migrate list orders both types at once. */
    val sources: StateFlow<List<NovelMigrateSource>?> = combine(
        novelRepository.getLibraryNovelAsFlow(),
        sourceManager.sources,
        novelPreferences.seenNovelSources().changes(),
    ) { libraryNovels, installedSources, cached ->
        val installed = installedSources.associate {
            it.id to LnSourceIdentity(name = it.name, iconUrl = it.iconUrl, lang = it.lang)
        }
        buildNovelMigrateSources(
            sourceIdsPerNovel = libraryNovels.map { it.novel.source },
            installed = installed,
            cached = cached,
        )
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
    val count: Int,
    val isInstalled: Boolean,
)

/**
 * Pure core: turn one source id per favorited novel into per-source rows with counts, resolving each
 * id's display identity as installed -> cached -> raw id. [installed] are the currently registered
 * sources; [cached] the last-known identities that survive an uninstall. A row with neither falls
 * back to its plugin id as the name and a null icon (the row's icon slot renders a book placeholder).
 */
internal fun buildNovelMigrateSources(
    sourceIdsPerNovel: List<String>,
    installed: Map<String, LnSourceIdentity>,
    cached: Map<String, LnSourceIdentity>,
): List<NovelMigrateSource> {
    return sourceIdsPerNovel.groupingBy { it }.eachCount().map { (id, count) ->
        val identity = installed[id] ?: cached[id]
        NovelMigrateSource(
            id = id,
            name = identity?.name ?: id,
            iconUrl = identity?.iconUrl,
            lang = identity?.lang.orEmpty(),
            count = count,
            isInstalled = id in installed,
        )
    }
}
