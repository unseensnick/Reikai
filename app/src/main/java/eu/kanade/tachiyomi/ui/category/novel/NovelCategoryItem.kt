package eu.kanade.tachiyomi.ui.category.novel

import android.view.View
import androidx.recyclerview.widget.RecyclerView
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.AbstractFlexibleItem
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.ui.category.novel.NovelCategoryPresenter.Companion.CREATE_CATEGORY_ORDER

/**
 * Novel-side counterpart to [eu.kanade.tachiyomi.ui.category.CategoryItem]. Reuses the
 * `categories_item.xml` layout (content-agnostic — title + edit button + drag handle).
 */
class NovelCategoryItem(val category: NovelCategory) :
    AbstractFlexibleItem<NovelCategoryHolder>() {

    var isEditing = false

    override fun getLayoutRes(): Int {
        return R.layout.categories_item
    }

    override fun createViewHolder(
        view: View,
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
    ): NovelCategoryHolder {
        return NovelCategoryHolder(view, adapter as NovelCategoryAdapter)
    }

    override fun bindViewHolder(
        adapter: FlexibleAdapter<IFlexible<RecyclerView.ViewHolder>>,
        holder: NovelCategoryHolder,
        position: Int,
        payloads: MutableList<Any>,
    ) {
        holder.bind(category)
        if (adapter.isSelected(position)) {
            holder.setSelectionVisual(true)
        } else {
            holder.isEditing(isEditing)
        }
    }

    override fun isSelectable(): Boolean = category.order != CREATE_CATEGORY_ORDER

    override fun isDraggable(): Boolean {
        return category.order != CREATE_CATEGORY_ORDER && !isEditing
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other is NovelCategoryItem) {
            return category.id == other.category.id
        }
        return false
    }

    override fun hashCode(): Int {
        return category.id!!
    }
}
