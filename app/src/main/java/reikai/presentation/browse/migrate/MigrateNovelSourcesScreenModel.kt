package reikai.presentation.browse.migrate

import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.domain.source.interactor.SetMigrateSorting
import eu.kanade.domain.source.service.SourcePreferences
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import reikai.domain.novel.LnSourceIdentity
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.NovelRepository
import reikai.novel.source.NovelSourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Novel side of the Browse migration source list: favorited novels grouped by their source, with a
 * count per source, sorted by the same migration sort preference manga uses. A source's display name
 * and icon resolve through a 3-step chain so a row shows even when its plugin is uninstalled (manga
 * stub parity): the live installed source, else the last-known [NovelPreferences.seenNovelSources]
 * cache, else the raw plugin id. Tapping a row opens [MigrateNovelScreen] for that source.
 */
class MigrateNovelSourcesScreenModel(
    private val novelRepository: NovelRepository = Injekt.get(),
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val novelPreferences: NovelPreferences = Injekt.get(),
    private val sourcePreferences: SourcePreferences = Injekt.get(),
    private val setMigrateSorting: SetMigrateSorting = Injekt.get(),
) : StateScreenModel<MigrateNovelSourcesScreenModel.State>(State()) {

    init {
        combine(
            novelRepository.getLibraryNovelAsFlow(),
            sourceManager.sources,
            novelPreferences.seenNovelSources().changes(),
            sourcePreferences.migrationSortingMode.changes(),
            sourcePreferences.migrationSortingDirection.changes(),
        ) { libraryNovels, installedSources, cached, mode, direction ->
            val installed = installedSources.associate {
                it.id to LnSourceIdentity(name = it.name, iconUrl = it.iconUrl, lang = it.lang)
            }
            val rows = buildNovelMigrateSources(
                sourceIdsPerNovel = libraryNovels.map { it.novel.source },
                installed = installed,
                cached = cached,
            )
            State(
                isLoading = false,
                items = sortNovelMigrateSources(rows, mode, direction),
                sortingMode = mode,
                sortingDirection = direction,
            )
        }
            .onEach { mutableState.value = it }
            .launchIn(screenModelScope)
    }

    fun toggleSortingMode() {
        with(state.value) {
            val newMode = when (sortingMode) {
                SetMigrateSorting.Mode.ALPHABETICAL -> SetMigrateSorting.Mode.TOTAL
                SetMigrateSorting.Mode.TOTAL -> SetMigrateSorting.Mode.ALPHABETICAL
            }
            setMigrateSorting.await(newMode, sortingDirection)
        }
    }

    fun toggleSortingDirection() {
        with(state.value) {
            val newDirection = when (sortingDirection) {
                SetMigrateSorting.Direction.ASCENDING -> SetMigrateSorting.Direction.DESCENDING
                SetMigrateSorting.Direction.DESCENDING -> SetMigrateSorting.Direction.ASCENDING
            }
            setMigrateSorting.await(sortingMode, newDirection)
        }
    }

    @Immutable
    data class State(
        val isLoading: Boolean = true,
        val items: List<NovelMigrateSource> = emptyList(),
        val sortingMode: SetMigrateSorting.Mode = SetMigrateSorting.Mode.ALPHABETICAL,
        val sortingDirection: SetMigrateSorting.Direction = SetMigrateSorting.Direction.ASCENDING,
    ) {
        val isEmpty = items.isEmpty()
    }
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

/** Order the rows by the shared migration sort: alphabetically by name or by favorite count, in the
 *  chosen direction. Kept separate from grouping so both stay independently testable. */
internal fun sortNovelMigrateSources(
    list: List<NovelMigrateSource>,
    mode: SetMigrateSorting.Mode,
    direction: SetMigrateSorting.Direction,
): List<NovelMigrateSource> {
    val base: Comparator<NovelMigrateSource> = when (mode) {
        SetMigrateSorting.Mode.ALPHABETICAL -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name }
        SetMigrateSorting.Mode.TOTAL -> compareBy { it.count }
    }
    return when (direction) {
        SetMigrateSorting.Direction.ASCENDING -> list.sortedWith(base)
        SetMigrateSorting.Direction.DESCENDING -> list.sortedWith(base.reversed())
    }
}
