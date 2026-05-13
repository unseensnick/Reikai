package eu.kanade.tachiyomi.ui.manga.related.browse

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.model.SManga

/**
 * Phase 6.5 — grid item for the full-screen related-mangas browse view. Reuses the carousel's
 * card layout ([R.layout.related_manga_card_item]) so visuals match.
 *
 * Identity is `(sourceId, manga.url)` — same key the selection state uses, since the carousel's
 * url-only [eu.kanade.tachiyomi.ui.manga.related.RelatedMangaCandidate.equals] can collide between
 * source and tracker URL spaces.
 */
class RelatedMangaBrowseItem(
    val sourceId: Long,
    val trackerName: String?,
    val manga: SManga,
) : AbstractFlexibleItem<RelatedMangaBrowseHolder>() {

    override fun getLayoutRes(): Int = R.layout.related_manga_card_item

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): RelatedMangaBrowseHolder = RelatedMangaBrowseHolder(view, adapter)

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: RelatedMangaBrowseHolder,
        position: Int,
        payloads: MutableList<Any?>?,
    ) {
        holder.bind(sourceId, manga, adapter.isSelected(position))
    }

    override fun equals(other: Any?): Boolean {
        if (other !is RelatedMangaBrowseItem) return false
        return sourceId == other.sourceId && manga.url == other.manga.url
    }

    override fun hashCode(): Int = 31 * sourceId.hashCode() + manga.url.hashCode()
}
