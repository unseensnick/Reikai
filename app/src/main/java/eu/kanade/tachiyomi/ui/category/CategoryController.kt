package eu.kanade.tachiyomi.ui.category

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.BaseTransientBottomBar
import com.google.android.material.snackbar.Snackbar
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.SelectableAdapter
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.databinding.CategoriesControllerBinding
import eu.kanade.tachiyomi.ui.base.SmallToolbarInterface
import eu.kanade.tachiyomi.ui.base.controller.BaseLegacyController
import eu.kanade.tachiyomi.ui.category.CategoryPresenter.Companion.CREATE_CATEGORY_ORDER
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.liftAppbarWith
import eu.kanade.tachiyomi.util.view.setAction
import eu.kanade.tachiyomi.util.view.setMessage
import eu.kanade.tachiyomi.util.view.setPositiveButton
import eu.kanade.tachiyomi.util.view.setTitle
import eu.kanade.tachiyomi.util.view.snack
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

/**
 * Controller to manage the categories for the users' library.
 */
class CategoryController(bundle: Bundle? = null) :
    BaseLegacyController<CategoriesControllerBinding>(bundle),
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    FlexibleAdapter.OnItemMoveListener,
    ActionMode.Callback,
    SmallToolbarInterface,
    CategoryAdapter.CategoryItemListener {

    /**
     * Adapter containing category items.
     */
    private var adapter: CategoryAdapter? = null

    /**
     * Undo helper used for restoring a deleted category.
     */
    private var snack: Snackbar? = null

    /**
     * ActionMode instance active during multi-select, null otherwise.
     */
    private var actionMode: ActionMode? = null

    /**
     * Categories currently selected in multi-select mode.
     */
    private val selectedCategories = mutableSetOf<Category>()

    /**
     * Creates the presenter for this controller. Not to be manually called.
     */
    private val presenter = CategoryPresenter(this)

    /**
     * Returns the toolbar title to show when this controller is attached.
     */
    override fun getTitle(): String? {
        return view?.context?.getString(MR.strings.edit_categories)
    }

    override fun createBinding(inflater: LayoutInflater) = CategoriesControllerBinding.inflate(inflater)

    /**
     * Called after view inflation. Used to initialize the view.
     *
     * @param view The view of this controller.
     */
    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        liftAppbarWith(binding.recycler, true, changeMarginsInstead = true)

        adapter = CategoryAdapter(this@CategoryController)
        binding.recycler.layoutManager = LinearLayoutManager(view.context)
        binding.recycler.setHasFixedSize(true)
        binding.recycler.adapter = adapter
        adapter?.isHandleDragEnabled = true
        adapter?.isPermanentDelete = false

        presenter.getCategories()
    }

    /**
     * Called when the view is being destroyed. Used to release references and remove callbacks.
     *
     * @param view The view of this controller.
     */
    override fun onDestroyView(view: View) {
        // Manually call callback to delete categories if required
        snack?.dismiss()
        view.clearFocus()
        confirmDelete()
        snack = null
        adapter = null
        super.onDestroyView(view)
    }

    override fun handleBack(): Boolean {
        view?.clearFocus()
        confirmDelete()
        return super.handleBack()
    }

    /**
     * Called from the presenter when the categories are updated.
     *
     * @param categories The new list of categories to display.
     */
    fun setCategories(categories: List<CategoryItem>) {
        adapter?.updateDataSet(categories)
    }

    /**
     * Called when an item in the list is clicked.
     *
     * @param position The position of the clicked item.
     * @return true if this click should enable selection mode.
     */
    override fun onItemClick(view: View?, position: Int): Boolean {
        if (actionMode != null) {
            toggleSelection(position)
            return true
        }
        adapter?.resetEditing(position)
        return true
    }

    /**
     * Called when an item in the list is long-clicked. Enters multi-select mode.
     *
     * @param position The position of the long-clicked item.
     */
    override fun onItemLongClick(position: Int) {
        val item = adapter?.getItem(position) ?: return
        if (item.category.order == CREATE_CATEGORY_ORDER) return
        snack?.dismiss()
        createActionModeIfNeeded()
        toggleSelection(position)
    }

    private fun createActionModeIfNeeded() {
        if (actionMode == null) {
            // Clear any active inline edit before entering selection mode so the
            // rename field and checkmark button are not accessible during multi-select.
            val adapter = adapter
            if (adapter != null) {
                for (i in 0 until adapter.itemCount) {
                    adapter.getItem(i)?.isEditing = false
                }
                adapter.notifyDataSetChanged()
            }
            actionMode = (activity as AppCompatActivity).startSupportActionMode(this)
            adapter?.mode = SelectableAdapter.Mode.MULTI
            adapter?.isHandleDragEnabled = false
        }
    }

    private fun destroyActionModeIfNeeded() {
        actionMode?.finish()
    }

    private fun toggleSelection(position: Int) {
        val item = adapter?.getItem(position) ?: return
        if (item.category.order == CREATE_CATEGORY_ORDER) return
        val nowSelected = adapter?.isSelected(position) != true
        if (nowSelected) {
            adapter?.addSelection(position)
            selectedCategories.add(item.category)
        } else {
            adapter?.removeSelection(position)
            selectedCategories.remove(item.category)
        }
        (binding.recycler.findViewHolderForAdapterPosition(position) as? CategoryHolder)
            ?.setSelectionVisual(nowSelected)
        if (selectedCategories.isEmpty()) destroyActionModeIfNeeded()
        else actionMode?.invalidate()
    }

    // region ActionMode.Callback

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.category_selection, menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.title = view?.context?.getString(MR.strings.selected_, selectedCategories.size)
        return true
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_select_all -> {
                val adapter = adapter ?: return true
                for (i in 0 until adapter.itemCount) {
                    val catItem = adapter.getItem(i) ?: continue
                    if (catItem.category.order == CREATE_CATEGORY_ORDER) continue
                    adapter.addSelection(i)
                    selectedCategories.add(catItem.category)
                    (binding.recycler.findViewHolderForAdapterPosition(i) as? CategoryHolder)
                        ?.setSelectionVisual(true)
                }
                actionMode?.invalidate()
                return true
            }
            R.id.action_deselect_all -> {
                val adapter = adapter ?: return true
                for (i in 0 until adapter.itemCount) {
                    if (adapter.isSelected(i)) {
                        adapter.removeSelection(i)
                        (binding.recycler.findViewHolderForAdapterPosition(i) as? CategoryHolder)
                            ?.setSelectionVisual(false)
                    }
                }
                selectedCategories.clear()
                destroyActionModeIfNeeded()
                return true
            }
            R.id.action_delete -> {
                deleteSelectedCategories()
                return true
            }
        }
        return false
    }

    override fun onDestroyActionMode(mode: ActionMode?) {
        selectedCategories.clear()
        actionMode = null
        adapter?.mode = SelectableAdapter.Mode.SINGLE
        adapter?.clearSelection()
        adapter?.isHandleDragEnabled = true
        adapter?.notifyDataSetChanged()
    }

    // endregion

    private fun deleteSelectedCategories() {
        val toDelete = selectedCategories.toList()
        if (toDelete.isEmpty()) return
        val count = toDelete.size
        activity!!.materialAlertDialog()
            .setTitle(view!!.context.getString(MR.strings.confirm_delete_categories, count))
            .setMessage(MR.strings.confirm_category_deletion_message)
            .setPositiveButton(MR.strings.delete) { _, _ ->
                destroyActionModeIfNeeded()
                val positions = toDelete
                    .mapNotNull { cat ->
                        (0 until (adapter?.itemCount ?: 0))
                            .firstOrNull { adapter?.getItem(it)?.category?.id == cat.id }
                    }
                    .sortedDescending()
                positions.forEach { adapter?.removeItem(it) }
                snack = view?.snack(
                    view!!.context.getString(MR.strings.categories_deleted, count),
                    5_000,
                ) {
                    var undoing = false
                    setAction(MR.strings.undo) {
                        adapter?.restoreDeletedItems()
                        undoing = true
                    }
                    addCallback(
                        object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                                super.onDismissed(transientBottomBar, event)
                                if (!undoing) confirmDelete()
                            }
                        },
                    )
                }
                (activity as? MainActivity)?.setUndoSnackBar(snack)
            }
            .setNegativeButton(AR.string.cancel, null)
            .show()
    }

    override fun onCategoryRename(position: Int, newName: String): Boolean {
        val category = adapter?.getItem(position)?.category ?: return false
        if (newName.isBlank()) {
            activity?.toast(MR.strings.category_cannot_be_blank)
            return false
        }
        if (category.order == CREATE_CATEGORY_ORDER) {
            return (presenter.createCategory(newName))
        }
        return (presenter.renameCategory(category, newName))
    }

    override fun onItemDelete(position: Int) {
        activity!!.materialAlertDialog()
            .setTitle(MR.strings.confirm_category_deletion)
            .setMessage(MR.strings.confirm_category_deletion_message)
            .setPositiveButton(MR.strings.delete) { _, _ ->
                deleteCategory(position)
            }
            .setNegativeButton(AR.string.cancel, null)
            .show()
    }

    private fun deleteCategory(position: Int) {
        confirmDelete()
        adapter?.removeItem(position)
        snack =
            view?.snack(MR.strings.category_deleted, 5_000) {
                var undoing = false
                setAction(MR.strings.undo) {
                    adapter?.restoreDeletedItems()
                    undoing = true
                }
                addCallback(
                    object : BaseTransientBottomBar.BaseCallback<Snackbar>() {
                        override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                            super.onDismissed(transientBottomBar, event)
                            if (!undoing) confirmDelete()
                        }
                    },
                )
            }
        (activity as? MainActivity)?.setUndoSnackBar(snack)
    }

    /**
     * Called when an item is released from a drag.
     *
     * @param position The position of the released item.
     */
    override fun onItemReleased(position: Int) {
        val adapter = adapter ?: return
        val categories = (0 until adapter.itemCount).mapNotNull { adapter.getItem(it)?.category }
        presenter.reorderCategories(categories)
    }

    fun confirmDelete() {
        val adapter = adapter ?: return
        presenter.deleteCategories(adapter.deletedItems.map { it.category })
        adapter.confirmDeletion()
        snack = null
    }

    override fun onActionStateChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {}

    override fun onItemMove(fromPosition: Int, toPosition: Int) {}

    override fun shouldMoveItem(fromPosition: Int, toPosition: Int): Boolean {
        return toPosition > 0
    }

    /**
     * Called from the presenter when a category with the given name already exists.
     */
    fun onCategoryExistsError() {
        activity?.toast(MR.strings.category_with_name_exists)
    }
}
