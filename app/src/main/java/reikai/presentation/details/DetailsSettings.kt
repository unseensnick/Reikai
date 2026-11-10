package reikai.presentation.details

import androidx.compose.runtime.Immutable
import eu.kanade.domain.ui.UiPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import reikai.domain.details.ReadButtonSide
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.hideTag
import reikai.domain.library.showOnlyTag
import reikai.domain.library.tagKey

/** Where the details chrome sits: the scroll actions' place and the Start/Resume button's side. */
@Immutable
data class DetailsChromePlacement(val jumpsInMenu: Boolean, val readButtonSide: ReadButtonSide)

/**
 * The settings the shared details body reads and writes for both content types, so none is read in
 * composition. Built per screen entry over the model's [scope], so it shares only while collected.
 */
class DetailsSettings(
    private val ui: UiPreferences,
    private val library: ReikaiLibraryPreferences,
    scope: CoroutineScope,
) {
    val chrome: StateFlow<DetailsChromePlacement> =
        combine(ui.detailsScrollActionsInMenu.changes(), ui.readButtonSide.changes(), ::DetailsChromePlacement)
            .stateIn(
                scope,
                SharingStarted.WhileSubscribed(),
                DetailsChromePlacement(ui.detailsScrollActionsInMenu.get(), ui.readButtonSide.get()),
            )

    /** Read once per page, as upstream's details header does. */
    val descriptionImages: Boolean = ui.imagesInDescription.get()

    fun dockReadButton(side: ReadButtonSide) = ui.readButtonSide.set(side)

    /** Sets the library's tag filter to show only [tag], or to hide it. */
    fun filterLibraryByTag(tag: String, show: Boolean) {
        val key = tagKey(tag)
        if (show) library.showOnlyTag(key) else library.hideTag(key)
    }
}
