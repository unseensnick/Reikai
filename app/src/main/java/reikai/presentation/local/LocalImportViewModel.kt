package reikai.presentation.local

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import mihon.core.metro.AppCoroutineScope
import reikai.data.local.CombinableSeries
import reikai.data.local.CombineLocalVolumes
import reikai.data.local.DuplicateChoice
import reikai.data.local.ImportGroup
import reikai.data.local.ImportProblem
import reikai.data.local.ImportResult
import reikai.data.local.LocalImport
import reikai.data.local.isLooseBook
import reikai.domain.library.ContentType
import reikai.presentation.browse.BatchCategories
import reikai.presentation.browse.MangaLibraryAdder
import reikai.presentation.novel.browse.NovelLibraryAdder
import tachiyomi.core.common.preference.CheckboxState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.category.model.Category

/**
 * The import screen's state: the files picked so far as series, what could not be read, and the import
 * itself. The copy runs on the app scope, so leaving the screen mid-import does not cut a file in half.
 */
@AssistedInject
class LocalImportViewModel(
    @Assisted initialUris: List<String>,
    private val localImport: LocalImport,
    private val combineLocalVolumes: CombineLocalVolumes,
    private val novelAdder: NovelLibraryAdder,
    private val mangaAdder: MangaLibraryAdder,
    @AppCoroutineScope private val appScope: CoroutineScope,
    handOff: ImportHandOff,
) : ViewModel() {

    val state: StateFlow<State>
        field = MutableStateFlow(State())

    init {
        if (initialUris.isNotEmpty()) add(initialUris.map(Uri::parse))
        viewModelScope.launch { handOff.files.collect(::add) }
    }

    fun add(uris: List<Uri>) {
        if (uris.isEmpty()) return
        state.update { it.copy(isReading = true) }
        viewModelScope.launchIO {
            val (groups, problems) = localImport.preview(uris)
            state.update { current ->
                val known = current.groups.flatMap { g -> g.files.map { it.uri } }.toSet() +
                    current.problems.mapNotNull { it.uri }
                val fresh = groups.map { g ->
                    g.copy(files = g.files.filterNot { it.uri in known })
                }.filter { it.files.isNotEmpty() }
                current.copy(
                    groups = merge(current.groups, fresh),
                    problems = current.problems + problems.filterNot { it.uri in known },
                    isReading = false,
                )
            }
            // A group that grew from a second round has new files to check against the series it joins.
            groups.forEach { reinspect(it.key) }
        }
    }

    fun rename(key: String, title: String) {
        updateGroup(key) { it.copy(title = title.trim().ifEmpty { it.title }) }
        // A new name can match another series already there, or none.
        reinspect(key, auto = true)
    }

    fun setSeparate(key: String, separate: Boolean) = updateGroup(key) { it.copy(separate = separate) }

    fun setDuplicateChoice(key: String, choice: DuplicateChoice) = updateGroup(key) { it.copy(onDuplicate = choice) }

    fun setNewerCopyChoice(key: String, choice: DuplicateChoice) = updateGroup(key) { it.copy(onNewerCopy = choice) }

    /**
     * The local series of [type] in the library a group can be added to. A loose book is left out, since it is a file and
     * not a folder to copy into; Add volumes on its details screen takes others in.
     */
    suspend fun librarySeries(type: ContentType): List<CombinableSeries> =
        combineLocalVolumes.library(type).filterNot { isLooseBook(type, it.folder) }

    /** Adds group [key]'s files to group [into] of this import, as more of its volumes. */
    fun addToGroup(key: String, into: String) {
        state.update { s ->
            val from = s.groups.firstOrNull { it.key == key } ?: return@update s
            val groups = s.groups.filterNot { it.key == key }.map { group ->
                if (group.key != into) return@map group
                group.copy(
                    files = group.files + from.files,
                    canBeEitherType =
                    group.canBeEitherType && from.canBeEitherType,
                )
            }
            s.copy(groups = groups)
        }
        reinspect(into)
    }

    /** Adds group [key] to the series already in [folder]. */
    fun addToSeries(key: String, folder: String) {
        updateGroup(key) { it.copy(separate = false) }
        reinspect(key, target = folder)
    }

    /** Finds again the series [key] joins, [target] or the one it joins now, or by its title when [auto]. */
    private fun reinspect(key: String, target: String? = null, auto: Boolean = false) {
        viewModelScope.launchIO {
            val group = state.value.groups.firstOrNull { it.key == key } ?: return@launchIO
            val found = localImport.inspect(group, if (auto) null else target ?: group.existing)
            updateGroup(key) {
                it.copy(
                    existing = found.existing,
                    duplicates = found.duplicates,
                    newerCopies = found.newerCopies,
                    onNewerCopy = found.onNewerCopy,
                )
            }
        }
    }

    /** Group [key] as [type], and the series it joins found again in that type's folder. */
    fun setType(key: String, type: ContentType) {
        val group = state.value.groups.firstOrNull { it.key == key } ?: return
        if (!group.canBeEitherType || group.type == type) return
        updateGroup(key) { it.copy(type = type, existing = null, duplicates = emptySet()) }
        reinspect(key, auto = true)
    }

    fun remove(key: String) = state.update { s -> s.copy(groups = s.groups.filterNot { it.key == key }) }

    fun setAddToLibrary(add: Boolean) = state.update { it.copy(addToLibrary = add) }

    /**
     * Ask for categories only where a type's batch rule says to, once per type, then copy and add. Import turns
     * off at once, since the category read leaves room for a second tap to start a second copy.
     */
    fun startImport() {
        val groups = state.value.groups
            .takeIf { it.isNotEmpty() && !state.value.isImporting && it.none(ImportGroup::asksAboutNewerCopies) }
            ?: return
        state.update { it.copy(pending = emptyMap(), isImporting = true) }
        viewModelScope.launchIO {
            val categories = mutableMapOf<ContentType, List<Long>>()
            if (state.value.addToLibrary) {
                for (type in groups.map { it.type }.distinct()) {
                    when (val batch = batchCategories(type)) {
                        is BatchCategories.Default -> categories[type] = batch.categoryIds
                        is BatchCategories.Ask -> {
                            state.update {
                                it.copy(
                                    pickCategories = CategoryPick(type, batch.initialSelection),
                                    pending = categories.toMap(),
                                    isImporting = false,
                                )
                            }
                            return@launchIO
                        }
                    }
                }
            }
            runImport(groups, categories)
        }
    }

    /** The picker's answer for [type]; the next type that asks is asked in turn. */
    fun confirmCategories(type: ContentType, categoryIds: List<Long>) {
        val chosen = state.value.pending + (type to categoryIds)
        state.update { it.copy(pickCategories = null, pending = chosen, isImporting = true) }
        val groups = state.value.groups
        viewModelScope.launchIO {
            for (next in groups.map { it.type }.distinct().filter { it !in chosen }) {
                val batch = batchCategories(next)
                if (batch is BatchCategories.Ask) {
                    state.update {
                        it.copy(pickCategories = CategoryPick(next, batch.initialSelection), isImporting = false)
                    }
                    return@launchIO
                }
                state.update {
                    it.copy(pending = it.pending + (next to (batch as BatchCategories.Default).categoryIds))
                }
            }
            runImport(groups, state.value.pending)
        }
    }

    // The dialog dismisses before it confirms, so this keeps the answers given; a new import resets them.
    fun dismissCategories() = state.update { it.copy(pickCategories = null) }

    private suspend fun batchCategories(type: ContentType): BatchCategories = when (type) {
        ContentType.NOVELS -> novelAdder.resolveBatchCategories()
        else -> mangaAdder.resolveBatchCategories()
    }

    private fun runImport(groups: List<ImportGroup>, categories: Map<ContentType, List<Long>>) {
        val addToLibrary = state.value.addToLibrary
        appScope.launchIO {
            val results = groups.groupBy { it.type }.map { (type, ofType) ->
                localImport.import(ofType, addToLibrary, categories[type].orEmpty())
            }
            val result = ImportResult(results.flatMap { it.series }, results.flatMap { it.problems })
            // Only what was imported leaves, so files shared in while it ran stay to be imported next.
            val imported = groups.flatMap { g -> g.files.map { it.uri } }.toSet()
            state.update { s ->
                val left = s.groups.map { g -> g.copy(files = g.files.filterNot { it.uri in imported }) }
                s.copy(isImporting = false, result = result, groups = left.filter { it.files.isNotEmpty() })
            }
        }
    }

    private fun updateGroup(key: String, change: (ImportGroup) -> ImportGroup) =
        state.update { s -> s.copy(groups = s.groups.map { if (it.key == key) change(it) else it }) }

    /** Files picked in a second round join the series the first round found. */
    private fun merge(current: List<ImportGroup>, fresh: List<ImportGroup>): List<ImportGroup> {
        val byKey = current.associateBy { it.key }.toMutableMap()
        for (group in fresh) {
            byKey[group.key] = byKey[group.key]?.let { it.copy(files = it.files + group.files) } ?: group
        }
        return byKey.values.toList()
    }

    data class CategoryPick(val type: ContentType, val initialSelection: List<CheckboxState.State<Category>>)

    data class State(
        val groups: List<ImportGroup> = emptyList(),
        val problems: List<ImportProblem> = emptyList(),
        val isReading: Boolean = false,
        val isImporting: Boolean = false,
        val addToLibrary: Boolean = true,
        val pickCategories: CategoryPick? = null,
        val pending: Map<ContentType, List<Long>> = emptyMap(),
        val result: ImportResult? = null,
    )

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(initialUris: List<String>): LocalImportViewModel
    }
}
