package eu.kanade.tachiyomi.ui.library

import android.content.Context
import androidx.annotation.CallSuper
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.StaggeredGridLayoutManager
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractSectionableItem
import eu.davidea.flexibleadapter.items.IFilterable
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.source.SourceManager
import uy.kohesive.injekt.injectLazy
import yokai.domain.ui.UiPreferences

abstract class LibraryItem(
    header: LibraryHeaderItem,
    context: Context?,
) : AbstractSectionableItem<LibraryHolder, LibraryHeaderItem>(header), IFilterable<String> {

    /**
     * Application context, not Activity. LibraryItems are emitted by [LibraryPresenter] into a
     * SharedFlow whose combine state outlives any single activity (Conductor keeps the controller
     * + presenter across recreates). If we stored the Activity context callers pass in, every
     * theme switch would leak the previous activity through `LibraryItem.context → MainActivity`.
     *
     * All downstream reads (`MangaCoverMetadata.setRatioAndColorsInScope`,
     * `manga.seriesType(context, ...)`) only need a Context for resource and string lookup; the
     * unbound Application reference is safe.
     */
    internal val context: Context? = context?.applicationContext

    var filter = ""

    internal val sourceManager: SourceManager by injectLazy()
    private val uiPreferences: UiPreferences by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    internal val uniformSize: Boolean
        get() = uiPreferences.uniformGrid().get()

    internal val libraryLayout: Int
        get() = preferences.libraryLayout().get()

    val hideReadingButton: Boolean
        get() = preferences.hideStartReadingButton().get()

    @CallSuper
    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: LibraryHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.onSetValues(this)
        (holder as? LibraryGridHolder)?.setSelected(adapter.isSelected(position))
        (holder.itemView.layoutParams as? StaggeredGridLayoutManager.LayoutParams)?.isFullSpan = this is LibraryPlaceholderItem
    }

    companion object {
        const val LAYOUT_LIST = 0
        const val LAYOUT_COMPACT_GRID = 1
        const val LAYOUT_COMFORTABLE_GRID = 2
        const val LAYOUT_COVER_ONLY_GRID = 3
    }
}
