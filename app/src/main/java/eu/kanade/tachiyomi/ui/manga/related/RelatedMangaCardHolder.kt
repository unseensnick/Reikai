package eu.kanade.tachiyomi.ui.manga.related

import android.view.View
import coil3.dispose
import eu.kanade.tachiyomi.databinding.RelatedMangaCardItemBinding
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import eu.kanade.tachiyomi.util.view.setCards
import yokai.domain.manga.models.MangaCover
import yokai.util.coil.loadManga

class RelatedMangaCardHolder(view: View, adapter: RelatedMangaCardAdapter) :
    BaseFlexibleViewHolder(view, adapter) {

    private val binding = RelatedMangaCardItemBinding.bind(view)

    init {
        itemView.setOnClickListener {
            val item = adapter.getItem(flexibleAdapterPosition) ?: return@setOnClickListener
            (adapter as RelatedMangaCardAdapter).mangaClickListener.onRelatedMangaClick(item.manga)
        }
        // Cards-style outlines match the rest of the app's manga thumbnails.
        setCards(adapter.showOutlines, binding.card, null)
    }

    fun bind(sourceId: Long, manga: SManga) {
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
    }
}
