package reikai.presentation.library.updateerror

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.data.library.LibraryUpdateWorker
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import reikai.data.novel.update.NovelUpdateWorker
import reikai.domain.entry.EntryId
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.entry.withCustomInfo
import reikai.domain.library.ContentType
import reikai.domain.library.includes
import reikai.domain.library.updateerror.DeleteLibraryUpdateErrors
import reikai.domain.library.updateerror.GetLibraryUpdateErrors
import reikai.domain.library.updateerror.LibraryUpdateError
import reikai.domain.novel.updateerror.DeleteNovelUpdateErrors
import reikai.domain.novel.updateerror.GetNovelUpdateErrors
import reikai.domain.novel.updateerror.NovelUpdateError
import reikai.novel.source.NovelSourceManager
import reikai.presentation.selection.EntrySelection
import reikai.presentation.selection.SelectionState
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.service.SourceManager

/**
 * One screen for both verticals' update failures, switched by the All / Manga / Novels chip. Manga
 * and novel errors live in separate tables (each FK-bound to its own library), so they are combined
 * only here at the presentation layer.
 */
@AssistedInject
class UpdateErrorsViewModel(
    @Assisted private val initialContentType: ContentType,
    private val getLibraryUpdateErrors: GetLibraryUpdateErrors,
    private val deleteLibraryUpdateErrors: DeleteLibraryUpdateErrors,
    private val getNovelUpdateErrors: GetNovelUpdateErrors,
    private val deleteNovelUpdateErrors: DeleteNovelUpdateErrors,
    private val sourceManager: SourceManager,
    private val novelSourceManager: NovelSourceManager,
    private val getEntryCustomInfo: GetEntryCustomInfo,
) : ViewModel() {

    val state: StateFlow<UpdateErrorsScreenState>
        field = MutableStateFlow<UpdateErrorsScreenState>(UpdateErrorsScreenState.Loading)

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(initialContentType: ContentType): UpdateErrorsViewModel
    }

    init {
        viewModelScope.launchIO {
            // Drop errors for entries no longer in the library before showing the list.
            runCatching { deleteLibraryUpdateErrors.nonFavorites() }
            runCatching { deleteNovelUpdateErrors.nonFavorites() }
            // The novel registry is empty until something loads the plugins, and a row would fall back
            // to its raw source id rather than the source name.
            novelSourceManager.ensureLoaded()
            combine(
                getLibraryUpdateErrors.subscribeAll(),
                getNovelUpdateErrors.subscribeAll(),
                getEntryCustomInfo.subscribeAll(),
            ) { mangaErrors, novelErrors, customInfo ->
                val manga = mangaErrors.map {
                    UpdateErrorEntry.Manga(
                        it.withCustomInfo(customInfo[EntryId.Manga(it.mangaId)]),
                        sourceManager.getOrStub(it.sourceId).name,
                    )
                }
                val novel = novelErrors.map {
                    UpdateErrorEntry.Novel(
                        it.withCustomInfo(customInfo[EntryId.Novel(it.novelId)]),
                        novelSourceManager.nameOf(it.source),
                    )
                }
                manga + novel
            }.collectLatest { entries ->
                state.update { current ->
                    val prev = current as? UpdateErrorsScreenState.Success
                    UpdateErrorsScreenState.Success(
                        entries = entries,
                        contentType = prev?.contentType ?: initialContentType,
                        selection = EntrySelection.retain(
                            prev?.selection ?: SelectionState(),
                            entries.map { it.entryId },
                        ),
                    )
                }
            }
        }
    }

    fun setContentType(type: ContentType) = state.update { state ->
        if (state !is UpdateErrorsScreenState.Success) return@update state
        val kept = EntrySelection.afterChipFlip(state.selection, state.contentType, type)
        state.copy(contentType = type, selection = kept)
    }

    fun toggleSelection(id: EntryId) = select { EntrySelection.toggle(it.selection, id) }

    fun rangeSelection(id: EntryId) = select { EntrySelection.rangeOrToggle(it.selection, id, it.orderedIds) }

    fun selectAll() = select { EntrySelection.selectAll(it.selection, it.orderedIds) }

    fun clearSelection() = select { EntrySelection.clear() }

    private fun select(verb: (UpdateErrorsScreenState.Success) -> SelectionState<EntryId>) = state.update { state ->
        if (state !is UpdateErrorsScreenState.Success) return@update state
        state.copy(selection = verb(state))
    }

    fun dismissSelected() {
        val state = state.value as? UpdateErrorsScreenState.Success ?: return
        val selectedEntries = state.selectedEntries
        if (selectedEntries.isEmpty()) return
        val mangaIds = selectedEntries.filterIsInstance<UpdateErrorEntry.Manga>().map { it.error.errorId }
        val novelIds = selectedEntries.filterIsInstance<UpdateErrorEntry.Novel>().map { it.error.errorId }
        viewModelScope.launchIO {
            if (mangaIds.isNotEmpty()) deleteLibraryUpdateErrors.byErrorIds(mangaIds)
            if (novelIds.isNotEmpty()) deleteNovelUpdateErrors.byErrorIds(novelIds)
        }
    }

    fun dismissAll() {
        val type = (state.value as? UpdateErrorsScreenState.Success)?.contentType ?: return
        viewModelScope.launchIO {
            if (type.includes(ContentType.MANGA)) deleteLibraryUpdateErrors.all()
            if (type.includes(ContentType.NOVELS)) deleteNovelUpdateErrors.all()
        }
    }

    /** Starts the update of each library behind the chip; true when at least one was not already running. */
    fun retry(context: Context): Boolean {
        val type = (state.value as? UpdateErrorsScreenState.Success)?.contentType ?: ContentType.ALL
        // Every start is attempted before asking whether any began, so a running manga update cannot
        // short-circuit the novel one.
        val started = buildList {
            if (type.includes(ContentType.MANGA)) add(LibraryUpdateWorker.startNow(context.workManager))
            if (type.includes(ContentType.NOVELS)) add(NovelUpdateWorker.startNow(context.workManager))
        }
        return started.any { it }
    }
}

@Immutable
sealed interface UpdateErrorsScreenState {

    @Immutable
    data object Loading : UpdateErrorsScreenState

    @Immutable
    data class Success(
        val entries: List<UpdateErrorEntry>,
        val contentType: ContentType = ContentType.ALL,
        val selection: SelectionState<EntryId> = SelectionState(),
    ) : UpdateErrorsScreenState {
        val visibleEntries: List<UpdateErrorEntry> get() =
            entries.filter { contentType.includes(it.entryId.contentType) }
        val groups: List<UpdateErrorGroup> get() = visibleEntries
            .groupBy { it.message }
            .map { (message, items) -> UpdateErrorGroup(message, items) }

        /** The rows in the order they are drawn, under their message headers: what a range runs over. */
        val orderedIds: List<EntryId> get() = groups.flatMap { group -> group.errors.map { it.entryId } }
        val isEmpty: Boolean get() = visibleEntries.isEmpty()
        val selectionMode: Boolean get() = !selection.isEmpty
        val selectedEntries: List<UpdateErrorEntry> get() = entries.filter { it.entryId in selection }
    }
}

@Immutable
data class UpdateErrorGroup(val message: String, val errors: List<UpdateErrorEntry>)

/** A failed entry from either vertical, normalized for the shared list. Each table holds at most one
 *  error per entry (its entry column is UNIQUE), so [entryId] identifies the row and drives selection. */
@Immutable
sealed interface UpdateErrorEntry {
    val entryId: EntryId
    val title: String
    val message: String
    val sourceName: String

    @Immutable
    data class Manga(val error: LibraryUpdateError, override val sourceName: String) : UpdateErrorEntry {
        override val entryId get() = EntryId.Manga(error.mangaId)
        override val title get() = error.mangaTitle
        override val message get() = error.message
    }

    @Immutable
    data class Novel(val error: NovelUpdateError, override val sourceName: String) : UpdateErrorEntry {
        override val entryId get() = EntryId.Novel(error.novelId)
        override val title get() = error.novelTitle
        override val message get() = error.message
    }
}
