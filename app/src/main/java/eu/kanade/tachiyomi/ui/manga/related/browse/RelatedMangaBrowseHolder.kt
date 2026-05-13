package eu.kanade.tachiyomi.ui.manga.related.browse

import android.view.View
import coil3.dispose
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.kanade.tachiyomi.databinding.RelatedMangaCardItemBinding
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import yokai.domain.manga.models.MangaCover
import yokai.util.coil.loadManga

/**
 * Phase 6.5 — browse-view grid holder. Reuses the carousel layout for visual continuity but lets
 * FlexibleAdapter's `OnItemClickListener` / `OnItemLongClickListener` drive click handling
 * (selection mode + open-on-tap). The carousel's [eu.kanade.tachiyomi.ui.manga.related
 * .RelatedMangaCardHolder] consumes clicks inline because it doesn't need selection support.
 *
 * Visual selection state: `itemView.isActivated` mirrors FlexibleAdapter's selection bit so the
 * `library_grid_selector` background (already on the card) lights up checked rows.
 */
class RelatedMangaBrowseHolder(
    view: View,
    adapter: FlexibleAdapter<*>,
) : BaseFlexibleViewHolder(view, adapter) {

    private val binding = RelatedMangaCardItemBinding.bind(view)

    fun bind(sourceId: Long, manga: SManga, isSelected: Boolean) {
        binding.title.text = manga.title
        binding.itemImage.dispose()
        if (!manga.thumbnail_url.isNullOrEmpty()) {
            val cover = MangaCover(
                mangaId = null,
                sourceId = sourceId,
                url = manga.thumbnail_url.orEmpty(),
                lastModified = 0L,
                inLibrary = false,
            )
            binding.itemImage.loadManga(cover, binding.progress)
        }
        itemView.isActivated = isSelected
    }
}
