package reikai.presentation.library.updateerror

import android.content.Context
import androidx.compose.runtime.Immutable
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import eu.kanade.tachiyomi.data.library.LibraryUpdateJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import reikai.domain.library.updateerror.DeleteLibraryUpdateErrors
import reikai.domain.library.updateerror.GetLibraryUpdateErrors
import reikai.domain.library.updateerror.LibraryUpdateError
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class UpdateErrorsScreenModel(
    private val getLibraryUpdateErrors: GetLibraryUpdateErrors = Injekt.get(),
    private val deleteLibraryUpdateErrors: DeleteLibraryUpdateErrors = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
) : StateScreenModel<UpdateErrorsScreenState>(UpdateErrorsScreenState.Loading) {

    init {
        screenModelScope.launchIO {
            // Drop errors for entries no longer in the library before showing the list.
            runCatching { deleteLibraryUpdateErrors.nonFavorites() }
            getLibraryUpdateErrors.subscribeAll().collectLatest { errors ->
                val groups = errors
                    .map { UpdateErrorItem(it, sourceManager.getOrStub(it.sourceId).name) }
                    .groupBy { it.error.message }
                    .map { (message, items) -> UpdateErrorGroup(message, items) }
                val validIds = errors.mapTo(mutableSetOf()) { it.errorId }
                mutableState.update { current ->
                    val kept = (current as? UpdateErrorsScreenState.Success)?.selected.orEmpty() intersect validIds
                    UpdateErrorsScreenState.Success(groups = groups, selected = kept)
                }
            }
        }
    }

    fun toggleSelection(errorId: Long) = mutableState.update { state ->
        if (state !is UpdateErrorsScreenState.Success) return@update state
        val selected = state.selected.toMutableSet().apply { if (!add(errorId)) remove(errorId) }
        state.copy(selected = selected)
    }

    fun selectAll() = mutableState.update { state ->
        if (state !is UpdateErrorsScreenState.Success) return@update state
        state.copy(selected = state.allErrorIds().toSet())
    }

    fun clearSelection() = mutableState.update { state ->
        if (state !is UpdateErrorsScreenState.Success) return@update state
        state.copy(selected = emptySet())
    }

    fun dismissSelected() {
        val ids = (state.value as? UpdateErrorsScreenState.Success)?.selected?.toList().orEmpty()
        if (ids.isEmpty()) return
        screenModelScope.launchIO { deleteLibraryUpdateErrors.byErrorIds(ids) }
    }

    fun dismissAll() {
        screenModelScope.launchIO { deleteLibraryUpdateErrors.all() }
    }

    fun retry(context: Context) {
        LibraryUpdateJob.startNow(context)
    }
}

@Immutable
sealed interface UpdateErrorsScreenState {

    @Immutable
    data object Loading : UpdateErrorsScreenState

    @Immutable
    data class Success(
        val groups: List<UpdateErrorGroup>,
        val selected: Set<Long> = emptySet(),
    ) : UpdateErrorsScreenState {
        val isEmpty: Boolean get() = groups.isEmpty()
        val selectionMode: Boolean get() = selected.isNotEmpty()
        fun allErrorIds(): List<Long> = groups.flatMap { group -> group.errors.map { it.error.errorId } }
    }
}

@Immutable
data class UpdateErrorGroup(val message: String, val errors: List<UpdateErrorItem>)

@Immutable
data class UpdateErrorItem(val error: LibraryUpdateError, val sourceName: String)
