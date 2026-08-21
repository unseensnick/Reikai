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
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import eu.kanade.tachiyomi.util.system.workManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import reikai.data.novel.update.NovelUpdateJob
import reikai.domain.library.ContentType
import reikai.domain.library.updateerror.DeleteLibraryUpdateErrors
import reikai.domain.library.updateerror.GetLibraryUpdateErrors
import reikai.domain.library.updateerror.LibraryUpdateError
import reikai.domain.novel.updateerror.DeleteNovelUpdateErrors
import reikai.domain.novel.updateerror.GetNovelUpdateErrors
import reikai.domain.novel.updateerror.NovelUpdateError
import reikai.novel.source.NovelSourceManager
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
            ) { mangaErrors, novelErrors ->
                val manga = mangaErrors.map {
                    UpdateErrorEntry.Manga(it, sourceManager.getOrStub(it.sourceId).name)
                }
                val novel = novelErrors.map {
                    UpdateErrorEntry.Novel(it, novelSourceManager.get(it.source)?.name ?: it.source)
                }
                manga + novel
            }.collectLatest { entries ->
                val validKeys = entries.mapTo(mutableSetOf()) { it.key }
                state.update { current ->
                    val prev = current as? UpdateErrorsScreenState.Success
                    UpdateErrorsScreenState.Success(
                        entries = entries,
                        contentType = prev?.contentType ?: initialContentType,
                        selected = prev?.selected.orEmpty() intersect validKeys,
                    )
                }
            }
        }
    }

    fun setContentType(type: ContentType) = state.update { state ->
        if (state !is UpdateErrorsScreenState.Success) return@update state
        // Selection is per-list; switching the chip drops a now-hidden selection.
        state.copy(contentType = type, selected = emptySet())
    }

    fun toggleSelection(key: String) = state.update { state ->
        if (state !is UpdateErrorsScreenState.Success) return@update state
        val selected = state.selected.toMutableSet().apply { if (!add(key)) remove(key) }
        state.copy(selected = selected)
    }

    fun selectAll() = state.update { state ->
        if (state !is UpdateErrorsScreenState.Success) return@update state
        state.copy(selected = state.visibleEntries.mapTo(mutableSetOf()) { it.key })
    }

    fun clearSelection() = state.update { state ->
        if (state !is UpdateErrorsScreenState.Success) return@update state
        state.copy(selected = emptySet())
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

    /** The manga ids of the current selection (their library ids, for migration). */
    fun selectedMangaIds(): List<Long> {
        val state = state.value as? UpdateErrorsScreenState.Success ?: return emptyList()
        return state.selectedEntries.filterIsInstance<UpdateErrorEntry.Manga>().map { it.error.mangaId }
    }

    /** The novel ids of the current selection (their library ids, for migration). */
    fun selectedNovelIds(): List<Long> {
        val state = state.value as? UpdateErrorsScreenState.Success ?: return emptyList()
        return state.selectedEntries.filterIsInstance<UpdateErrorEntry.Novel>().map { it.error.novelId }
    }

    fun dismissAll() {
        val type = (state.value as? UpdateErrorsScreenState.Success)?.contentType ?: return
        viewModelScope.launchIO {
            if (type != ContentType.NOVELS) deleteLibraryUpdateErrors.all()
            if (type != ContentType.MANGA) deleteNovelUpdateErrors.all()
        }
    }

    fun retry(context: Context) {
        val type = (state.value as? UpdateErrorsScreenState.Success)?.contentType ?: ContentType.ALL
        if (type != ContentType.NOVELS) LibraryUpdateJob.startNow(context.workManager)
        if (type != ContentType.MANGA) NovelUpdateJob.startNow(context.workManager)
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
        val selected: Set<String> = emptySet(),
    ) : UpdateErrorsScreenState {
        val visibleEntries: List<UpdateErrorEntry> get() = when (contentType) {
            ContentType.ALL -> entries
            ContentType.MANGA -> entries.filterIsInstance<UpdateErrorEntry.Manga>()
            ContentType.NOVELS -> entries.filterIsInstance<UpdateErrorEntry.Novel>()
        }
        val groups: List<UpdateErrorGroup> get() = visibleEntries
            .groupBy { it.message }
            .map { (message, items) -> UpdateErrorGroup(message, items) }
        val isEmpty: Boolean get() = visibleEntries.isEmpty()
        val selectionMode: Boolean get() = selected.isNotEmpty()
        val selectedEntries: List<UpdateErrorEntry> get() = entries.filter { it.key in selected }

        /** True when the selection is non-empty and all one vertical. Migration runs one vertical
         *  at a time, so a mixed manga + novel selection can't be migrated together. */
        val selectionIsSingleVertical: Boolean get() {
            val hasManga = selectedEntries.any { it is UpdateErrorEntry.Manga }
            val hasNovel = selectedEntries.any { it is UpdateErrorEntry.Novel }
            return hasManga != hasNovel
        }
    }
}

@Immutable
data class UpdateErrorGroup(val message: String, val errors: List<UpdateErrorEntry>)

/** A failed entry from either vertical, normalized for the shared list. [key] is unique across both
 *  tables (their error ids can collide), so it drives selection. */
@Immutable
sealed interface UpdateErrorEntry {
    val key: String
    val title: String
    val message: String
    val sourceName: String

    @Immutable
    data class Manga(val error: LibraryUpdateError, override val sourceName: String) : UpdateErrorEntry {
        override val key get() = "m:${error.errorId}"
        override val title get() = error.mangaTitle
        override val message get() = error.message
    }

    @Immutable
    data class Novel(val error: NovelUpdateError, override val sourceName: String) : UpdateErrorEntry {
        override val key get() = "n:${error.errorId}"
        override val title get() = error.novelTitle
        override val message get() = error.message
    }
}
