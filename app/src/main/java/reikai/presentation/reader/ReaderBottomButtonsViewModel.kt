package reikai.presentation.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderPreferences
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import reikai.domain.novel.NovelPreferences

/**
 * Arranging one reader's bottom-bar buttons: which show, and in what order. Each reader keeps its own
 * pair of preferences, so the screen edits the pair its [scope] names and never the other reader's.
 */
@AssistedInject
class ReaderBottomButtonsViewModel(
    @Assisted private val scope: ReaderBottomButton.Scope,
    readerPreferences: ReaderPreferences,
    novelPreferences: NovelPreferences,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(scope: ReaderBottomButton.Scope): ReaderBottomButtonsViewModel
    }

    data class Row(val button: ReaderBottomButton, val enabled: Boolean)

    private val preferences = ReaderBottomButton.BarPreferences.of(scope, readerPreferences, novelPreferences)
    private val selection = preferences.selection
    private val order = preferences.order

    val state: StateFlow<List<Row>> = combine(selection.changes(), order.changes()) { _, _ -> rows() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, rows())

    fun rows(): List<Row> {
        val selected = selection.get()
        return ReaderBottomButton.arranged(order.get(), scope).map {
            Row(it, it == ReaderBottomButton.Settings || it.value in selected)
        }
    }

    fun toggle(button: ReaderBottomButton) {
        val selected = selection.get()
        selection.set(if (button.value in selected) selected - button.value else selected + button.value)
    }

    /** Stores the whole arrangement, switched-off buttons included, so each keeps its place. */
    fun move(buttons: List<ReaderBottomButton>) {
        order.set(buttons.map { it.value })
    }
}
