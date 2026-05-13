package eu.kanade.tachiyomi.ui.manga.related

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R

/**
 * Phase 6.5 — synthetic trailing card in the related-mangas carousel. Tapping it opens the
 * full-screen browse view with the unbounded pool. Only appended by [MangaHeaderHolder] when the
 * full pool has more entries than fit in the 30-cap carousel.
 *
 * Identity is a singleton — there's never more than one "See all" card per carousel — so
 * [equals]/[hashCode] are constant.
 */
class SeeAllCardItem(val count: Int) : AbstractFlexibleItem<SeeAllCardHolder>() {

    override fun getLayoutRes(): Int = R.layout.related_manga_see_all_card

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): SeeAllCardHolder {
        return SeeAllCardHolder(view, adapter as RelatedMangaCardAdapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: SeeAllCardHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.bind(count)
    }

    override fun equals(other: Any?): Boolean = other is SeeAllCardItem

    override fun hashCode(): Int = SEE_ALL_HASH

    companion object {
        private const val SEE_ALL_HASH = 7411
    }
}
