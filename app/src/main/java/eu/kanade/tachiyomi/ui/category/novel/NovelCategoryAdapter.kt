package eu.kanade.tachiyomi.ui.category.novel

import eu.davidea.flexibleadapter.FlexibleAdapter

/**
 * Novel-side counterpart to [eu.kanade.tachiyomi.ui.category.CategoryAdapter]. Same FastAdapter
 * shape; types swap to [NovelCategoryItem] / [NovelCategoryController].
 */
class NovelCategoryAdapter(controller: NovelCategoryController) :
    FlexibleAdapter<NovelCategoryItem>(null, controller, true) {

    val categoryItemListener: CategoryItemListener = controller

    fun resetEditing(position: Int) {
        for (i in 0..itemCount) {
            getItem(i)?.isEditing = false
        }
        getItem(position)?.isEditing = true
        notifyDataSetChanged()
    }

    interface CategoryItemListener {
        fun onItemReleased(position: Int)
        fun onCategoryRename(position: Int, newName: String): Boolean
        fun onItemDelete(position: Int)
    }
}
