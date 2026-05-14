package eu.kanade.tachiyomi.ui.manga.related

import android.view.View
import eu.kanade.tachiyomi.databinding.RelatedMangaSeeAllCardBinding
import eu.kanade.tachiyomi.ui.base.holder.BaseFlexibleViewHolder
import yokai.i18n.MR
import yokai.util.lang.getString

class SeeAllCardHolder(view: View, adapter: RelatedMangaCardAdapter) :
    BaseFlexibleViewHolder(view, adapter) {

    private val binding = RelatedMangaSeeAllCardBinding.bind(view)

    init {
        itemView.setOnClickListener {
            val item = adapter.getItem(flexibleAdapterPosition) as? SeeAllCardItem
                ?: return@setOnClickListener
            (adapter as RelatedMangaCardAdapter).seeAllClickListener.onRelatedMangaSeeAllClick(item)
        }
        // Same as RelatedMangaCardHolder — consume long-press so the chapter context menu
        // doesn't surface from the outer adapter.
        itemView.setOnLongClickListener { true }
    }

    fun bind(count: Int) {
        binding.seeAllLabel.text = itemView.context.getString(MR.strings.see_all_count, count)
    }
}
