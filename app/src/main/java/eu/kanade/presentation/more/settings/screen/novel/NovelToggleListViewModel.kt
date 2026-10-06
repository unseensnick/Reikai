package eu.kanade.presentation.more.settings.screen.novel

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import reikai.novel.content.NovelStoredItem
import reikai.novel.content.NovelStoredToggleList

@Immutable
data class NovelToggleListState<T>(
    val items: List<T> = emptyList(),
    val dialog: NovelToggleListDialog<T>? = null,
)

sealed interface NovelToggleListDialog<T> {
    /** A null [item] is the add case, so one dialog serves both and cannot drift between them. */
    data class Edit<T>(val item: T?) : NovelToggleListDialog<T>
    data class Delete<T>(val item: T) : NovelToggleListDialog<T>
}

/** A settings screen over one stored list: its entries, and the dialogs that add, edit and delete them. */
abstract class NovelToggleListViewModel<T : NovelStoredItem<T>>(
    private val list: NovelStoredToggleList<T>,
) : ViewModel() {

    private val dialog = MutableStateFlow<NovelToggleListDialog<T>?>(null)

    val state: StateFlow<NovelToggleListState<T>> = combine(list.items, dialog, ::NovelToggleListState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), NovelToggleListState())

    fun showDialog(dialog: NovelToggleListDialog<T>) {
        this.dialog.value = dialog
    }

    fun dismissDialog() {
        dialog.value = null
    }

    fun save(item: T) {
        list.save(item)
        dismissDialog()
    }

    fun delete(item: T) {
        list.delete(item)
        dismissDialog()
    }

    fun toggle(item: T) {
        list.toggle(item)
    }
}
