package eu.kanade.tachiyomi.ui.category.novel

import android.app.Activity
import android.app.Dialog
import android.content.DialogInterface
import android.os.Bundle
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.databinding.MangaCategoryDialogBinding
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.library.LibrarySort
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.view.setPositiveButton
import eu.kanade.tachiyomi.util.view.setTitle
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.widget.TriStateCheckBox
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.injectLazy
import yokai.domain.novel.NovelPreferences
import yokai.domain.novel.interactor.GetNovelCategories
import yokai.domain.novel.interactor.InsertNovelCategories
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

/**
 * Novel-side counterpart to [eu.kanade.tachiyomi.ui.category.ManageCategoryDialog]. Reuses the
 * `manga_category_dialog.xml` layout (binding name is legacy; the XML itself is generic). The
 * `download_new` TriStateCheckBox is hidden because novel downloads aren't wired yet (Phase 7
 * Decision #4). The `include_global` toggle drives [NovelPreferences.libraryUpdateCategories]
 * + [NovelPreferences.libraryUpdateCategoriesExclude], which the existing novel library update
 * job already honours.
 */
class ManageNovelCategoryDialog(bundle: Bundle? = null) : DialogController(bundle) {

    constructor(category: NovelCategory?, updateLibrary: ((Int?) -> Unit)) : this() {
        this.updateLibrary = updateLibrary
        this.category = category
    }

    private var updateLibrary: ((Int?) -> Unit)? = null
    private var category: NovelCategory? = null

    private val novelPrefs by injectLazy<NovelPreferences>()
    private val getNovelCategories by injectLazy<GetNovelCategories>()
    private val insertNovelCategories by injectLazy<InsertNovelCategories>()

    lateinit var binding: MangaCategoryDialogBinding

    override fun onCreateDialog(savedViewState: Bundle?): Dialog {
        val dialog = dialog(activity!!).create()
        onViewCreated()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE)?.setOnClickListener {
                if (onPositiveButtonClick()) {
                    dialog.dismiss()
                }
            }
        }
        return dialog
    }

    fun dialog(activity: Activity): MaterialAlertDialogBuilder {
        return activity.materialAlertDialog().apply {
            setTitle(if (category == null) MR.strings.new_category else MR.strings.manage_category)
            binding = MangaCategoryDialogBinding.inflate(activity.layoutInflater)
            setView(binding.root)
            setNegativeButton(AR.string.cancel, null)
            setPositiveButton(MR.strings.save) { dialog, _ ->
                if (onPositiveButtonClick()) {
                    dialog.dismiss()
                }
            }
        }
    }

    fun show(activity: Activity) {
        val dialog = dialog(activity).create()
        onViewCreated()
        dialog.setOnShowListener {
            binding.title.requestFocus()
        }
        dialog.show()
    }

    private fun onPositiveButtonClick(): Boolean {
        val text = binding.title.text.toString()
        val categoryExists = categoryExists(text)
        val category = this.category ?: NovelCategory.create(text)
        if (category.id != 0) {
            if (text.isNotBlank() && !categoryExists &&
                !text.equals(this.category?.name ?: "", true)
            ) {
                category.name = text
                if (this.category == null) {
                    val categories = runBlocking { getNovelCategories.await() }
                    category.order = (categories.maxOfOrNull { it.order } ?: 0) + 1
                    category.novelSort = LibrarySort.Title.categoryValue
                    category.id = runBlocking { insertNovelCategories.awaitOne(category) }?.toInt()
                    this.category = category
                } else {
                    runBlocking { insertNovelCategories.awaitOne(category) }
                }
            } else if (categoryExists) {
                binding.categoryTextLayout.error =
                    binding.categoryTextLayout.context.getString(MR.strings.category_with_name_exists)
                return false
            } else if (text.isBlank()) {
                binding.categoryTextLayout.error =
                    binding.categoryTextLayout.context.getString(MR.strings.category_cannot_be_blank)
                return false
            }
        }
        // Novel downloads aren't wired yet (Phase 7 Decision #4), so skip the download_new
        // pref entirely on the novel dialog. The toggle is hidden in onViewCreated below.
        if (novelPrefs.libraryUpdateInterval().get() > 0 &&
            updatePref(
                    novelPrefs.libraryUpdateCategories(),
                    novelPrefs.libraryUpdateCategoriesExclude(),
                    binding.includeGlobal,
                ) == false
        ) {
            novelPrefs.libraryUpdateInterval().set(0)
        }
        updateLibrary?.invoke(category.id)
        return true
    }

    private fun categoryExists(name: String): Boolean {
        return runBlocking { getNovelCategories.await() }.any {
            it.name.equals(name, true) && category?.id != it.id
        }
    }

    fun onViewCreated() {
        if ((category?.id ?: 0) <= 0 && category != null) {
            binding.categoryTextLayout.isVisible = false
        }
        // Hide the download-new toggle entirely on the novel dialog — novel downloads aren't a
        // thing yet, so the pref has no consumer to fan out to.
        binding.downloadNew.isVisible = false
        // Hide the manga-side "Edit categories" link button; the novel categories management
        // is already on this screen (this dialog is hosted by it), so the link would loop back.
        binding.editCategories.isVisible = false
        binding.title.addTextChangedListener {
            binding.categoryTextLayout.error = null
        }
        binding.title.hint =
            category?.name ?: binding.editCategories.context.getString(MR.strings.category)
        binding.title.append(category?.name ?: "")
        setCheckbox(
            binding.includeGlobal,
            novelPrefs.libraryUpdateCategories(),
            novelPrefs.libraryUpdateCategoriesExclude(),
            novelPrefs.libraryUpdateInterval().get() > 0,
        )
    }

    /** Update a pref based on checkbox, and return if the pref is not empty. Same shape as
     *  the manga side. */
    private fun updatePref(
        categories: Preference<Set<String>>,
        excludeCategories: Preference<Set<String>>,
        box: TriStateCheckBox,
    ): Boolean? {
        val categoryId = category?.id ?: return null
        if (!box.isVisible) return null
        val updateCategories = categories.get().toMutableSet()
        val excludeUpdateCategories = excludeCategories.get().toMutableSet()
        when (box.state) {
            TriStateCheckBox.State.CHECKED -> {
                updateCategories.add(categoryId.toString())
                excludeUpdateCategories.remove(categoryId.toString())
            }
            TriStateCheckBox.State.IGNORE -> {
                updateCategories.remove(categoryId.toString())
                excludeUpdateCategories.add(categoryId.toString())
            }
            TriStateCheckBox.State.UNCHECKED -> {
                updateCategories.remove(categoryId.toString())
                excludeUpdateCategories.remove(categoryId.toString())
            }
        }
        categories.set(updateCategories)
        excludeCategories.set(excludeUpdateCategories)
        return updateCategories.isNotEmpty()
    }

    private fun setCheckbox(
        box: TriStateCheckBox,
        categories: Preference<Set<String>>,
        excludeCategories: Preference<Set<String>>,
        shouldShow: Boolean,
    ) {
        val updateCategories = categories.get()
        val excludeUpdateCategories = excludeCategories.get()
        box.isVisible = (updateCategories.isNotEmpty() || excludeUpdateCategories.isNotEmpty()) && shouldShow
        if (shouldShow) {
            box.state = when {
                updateCategories.any { category?.id == it.toIntOrNull() } -> TriStateCheckBox.State.CHECKED
                excludeUpdateCategories.any { category?.id == it.toIntOrNull() } -> TriStateCheckBox.State.IGNORE
                else -> TriStateCheckBox.State.UNCHECKED
            }
        }
    }
}
