package eu.kanade.presentation.more.settings.screen.novel

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import reikai.domain.novel.NovelPreferences
import reikai.novel.content.NovelCodeSnippet
import reikai.novel.content.NovelSnippetKind
import reikai.novel.content.NovelSnippets

@Immutable
data class NovelCodeSnippetsState(
    val snippets: List<NovelCodeSnippet> = emptyList(),
    val dialog: NovelCodeSnippetDialog? = null,
)

sealed interface NovelCodeSnippetDialog {
    /** A null [snippet] is the add case, as the find-and-replace rules have it. */
    data class Edit(val snippet: NovelCodeSnippet?) : NovelCodeSnippetDialog
    data class Delete(val snippet: NovelCodeSnippet) : NovelCodeSnippetDialog
}

/** One kind of snippet, CSS or JavaScript, each its own list in its own preference. */
@AssistedInject
class NovelCodeSnippetsViewModel(
    @Assisted private val kind: NovelSnippetKind,
    novelPreferences: NovelPreferences,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(kind: NovelSnippetKind): NovelCodeSnippetsViewModel
    }

    private val preference = novelPreferences.readerSnippets(kind)
    private val dialog = MutableStateFlow<NovelCodeSnippetDialog?>(null)

    val state: StateFlow<NovelCodeSnippetsState> = combine(preference.changes(), dialog) { json, dialog ->
        NovelCodeSnippetsState(NovelSnippets.decode(json), dialog)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), NovelCodeSnippetsState())

    fun showDialog(dialog: NovelCodeSnippetDialog) {
        this.dialog.value = dialog
    }

    fun dismissDialog() {
        dialog.value = null
    }

    /** Replaces the snippet sharing [snippet]'s id, or appends it when it is new. */
    fun save(snippet: NovelCodeSnippet) {
        val current = snippets()
        write(
            if (current.any { it.id == snippet.id }) {
                current.map { if (it.id == snippet.id) snippet else it }
            } else {
                current + snippet
            },
        )
        dismissDialog()
    }

    fun delete(snippet: NovelCodeSnippet) {
        write(snippets().filterNot { it.id == snippet.id })
        dismissDialog()
    }

    fun toggle(snippet: NovelCodeSnippet) {
        write(snippets().map { if (it.id == snippet.id) it.copy(enabled = !it.enabled) else it })
    }

    // Read back from the preference rather than from state, so a save cannot drop an edit made while
    // the dialog was open.
    private fun snippets() = NovelSnippets.decode(preference.get())

    private fun write(snippets: List<NovelCodeSnippet>) {
        preference.set(NovelSnippets.encode(snippets))
    }
}
