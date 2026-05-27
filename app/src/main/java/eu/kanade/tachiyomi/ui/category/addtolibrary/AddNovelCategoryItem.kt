package eu.kanade.tachiyomi.ui.category.addtolibrary

import android.view.View
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.items.AbstractItem
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.databinding.AddCategoryItemBinding
import eu.kanade.tachiyomi.widget.TriStateCheckBox

/**
 * Novel-side counterpart to [AddCategoryItem]. Shapewise identical: reads `category.name` for
 * the row label and uses `category.id` for the FastAdapter identifier. Separated because the
 * sheet that hosts these items needs the concrete [NovelCategory] type to build
 * `NovelInCategory` rows for persistence, and `Category` and `NovelCategory` are sibling
 * interfaces under `ILibraryCategory` rather than one extending the other.
 */
class AddNovelCategoryItem(val category: NovelCategory) :
    AbstractItem<FastAdapter.ViewHolder<AddNovelCategoryItem>>() {

    override val type: Int = R.id.category_checkbox

    override val layoutRes: Int = R.layout.add_category_item

    override var identifier = category.id?.toLong() ?: -1L

    var state: TriStateCheckBox.State = TriStateCheckBox.State.UNCHECKED
        set(value) {
            field = value
            isSelected = value != TriStateCheckBox.State.UNCHECKED
        }
    var skipInversed = false

    override fun getViewHolder(v: View): FastAdapter.ViewHolder<AddNovelCategoryItem> {
        return ViewHolder(v)
    }

    class ViewHolder(view: View) : FastAdapter.ViewHolder<AddNovelCategoryItem>(view) {

        val binding = AddCategoryItemBinding.bind(view)

        init {
            binding.categoryCheckbox.useIndeterminateForIgnore = true
        }

        override fun bindView(item: AddNovelCategoryItem, payloads: List<Any>) {
            binding.categoryCheckbox.skipInversed = item.skipInversed
            binding.categoryCheckbox.text = item.category.name
            binding.categoryCheckbox.state = item.state
        }

        override fun unbindView(item: AddNovelCategoryItem) {
            binding.categoryCheckbox.text = ""
            binding.categoryCheckbox.isChecked = false
        }
    }
}
