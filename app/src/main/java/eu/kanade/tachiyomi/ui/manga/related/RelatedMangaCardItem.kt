package eu.kanade.tachiyomi.ui.manga.related

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.model.SManga

/**
 * FlexibleAdapter item wrapping a single [SManga] suggestion for the related-mangas carousel.
 * Identity is by (sourceId, url) since source-side suggestions don't carry a stable id.
 */
class RelatedMangaCardItem(
    val sourceId: Long,
    val manga: SManga,
) : AbstractFlexibleItem<RelatedMangaCardHolder>() {

    override fun getLayoutRes(): Int = R.layout.related_manga_card_item

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): RelatedMangaCardHolder {
        return RelatedMangaCardHolder(view, adapter as RelatedMangaCardAdapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: RelatedMangaCardHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.bind(sourceId, manga)
    }

    override fun equals(other: Any?): Boolean {
        if (other !is RelatedMangaCardItem) return false
        return sourceId == other.sourceId && manga.url == other.manga.url
    }

    override fun hashCode(): Int {
        return 31 * sourceId.hashCode() + manga.url.hashCode()
    }
}
