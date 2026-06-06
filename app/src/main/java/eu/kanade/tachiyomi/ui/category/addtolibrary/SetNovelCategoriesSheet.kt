package eu.kanade.tachiyomi.ui.category.addtolibrary

import android.app.Activity
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.mikepenz.fastadapter.FastAdapter
import com.mikepenz.fastadapter.adapters.ItemAdapter
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.data.database.models.NovelInCategory
import eu.kanade.tachiyomi.databinding.SetCategoriesSheetBinding
import eu.kanade.tachiyomi.ui.category.novel.ManageNovelCategoryDialog
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.view.checkHeightThen
import eu.kanade.tachiyomi.util.view.expand
import eu.kanade.tachiyomi.widget.E2EBottomSheetDialog
import eu.kanade.tachiyomi.widget.TriStateCheckBox
import java.util.Locale
import kotlin.math.max
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import uy.kohesive.injekt.injectLazy
import yokai.domain.novel.interactor.GetNovelCategories
import yokai.domain.novel.interactor.SetNovelCategories
import yokai.domain.novel.models.Novel
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Novel-side counterpart to [SetCategoriesSheet]. Direct port: the only behavioural differences
 * are the data types (Novel / NovelCategory / NovelInCategory) and the persistence interactor
 * ([SetNovelCategories]). The XML layout, tri-state checkbox widget, and the
 * [AddNovelCategoryItem] FastAdapter row class follow the manga side one-for-one.
 *
 * Differences from the manga sheet:
 *  - `addingToLibrary` mode is exposed for symmetry but doesn't flip a `favorite` flag on the
 *    novel (the novel side doesn't yet have an UpdateNovel interactor with favourite + dateAdded
 *    semantics; the action-mode flow that drives this sheet only operates on already-favourited
 *    novels, so the move-only path is enough for v1).
 *  - The inline "+ New category" button is hidden — [eu.kanade.tachiyomi.ui.category.ManageCategoryDialog]
 *    is wired to the manga `Category` model; the novel-side equivalent ships with
 *    `NovelCategoriesScreen` (D3), so the sheet stays move-only for now.
 */
class SetNovelCategoriesSheet(
    private val activity: Activity,
    private val listNovel: List<Novel>,
    var categories: MutableList<NovelCategory>,
    var preselected: Array<TriStateCheckBox.State>,
    private val addingToLibrary: Boolean,
    val onNovelsAdded: (() -> Unit) = { },
) : E2EBottomSheetDialog<SetCategoriesSheetBinding>(activity) {

    constructor(
        activity: Activity,
        novel: Novel,
        categories: MutableList<NovelCategory>,
        preselected: Array<Int>,
        addingToLibrary: Boolean,
        onNovelsAdded: () -> Unit,
    ) : this(
        activity,
        listOf(novel),
        categories,
        categories.map {
            if (it.id in preselected) {
                TriStateCheckBox.State.CHECKED
            } else {
                TriStateCheckBox.State.UNCHECKED
            }
        }.toTypedArray(),
        addingToLibrary,
        onNovelsAdded,
    )

    private val fastAdapter: FastAdapter<AddNovelCategoryItem>
    private val itemAdapter = ItemAdapter<AddNovelCategoryItem>()

    private val getNovelCategories: GetNovelCategories by injectLazy()
    private val setNovelCategories: SetNovelCategories by injectLazy()

    override var recyclerView: RecyclerView? = binding.categoryRecyclerView

    private val preCheckedCategories = categories.mapIndexedNotNull { index, category ->
        category.takeIf { preselected[index] == TriStateCheckBox.State.CHECKED }
    }
    private val preIndeterminateCategories = categories.mapIndexedNotNull { index, category ->
        category.takeIf { preselected[index] == TriStateCheckBox.State.IGNORE }
    }
    private val selectedCategories = preIndeterminateCategories + preCheckedCategories

    private val selectedItems: Set<AddNovelCategoryItem>
        get() = itemAdapter.adapterItems.filter { it.isSelected }.toSet()

    private val checkedItems: Set<AddNovelCategoryItem>
        get() = itemAdapter.adapterItems.filter { it.state == TriStateCheckBox.State.CHECKED }.toSet()

    private val indeterminateItems: Set<AddNovelCategoryItem>
        get() = itemAdapter.adapterItems.filter { it.state == TriStateCheckBox.State.IGNORE }.toSet()

    private val uncheckedItems: Set<AddNovelCategoryItem>
        get() = itemAdapter.adapterItems.filter { !it.isSelected }.toSet()

    override fun createBinding(inflater: LayoutInflater) =
        SetCategoriesSheetBinding.inflate(inflater)

    init {
        // Title format keeps the existing "Move %s to" / "Add %s to" string and feeds it the
        // generic "selection" label. The manga side uses series-type strings (manga / manhwa /
        // ...) per-novel, but the novel side has no equivalent type taxonomy yet; "selection"
        // is the same label used when multiple manga are selected, so users have seen it.
        binding.toolbarTitle.text = context.getString(
            if (addingToLibrary) MR.strings.add_x_to else MR.strings.move_x_to,
            context.getString(MR.strings.selection).lowercase(Locale.ROOT),
        )

        setOnShowListener {
            updateBottomButtons()
        }
        sheetBehavior.addBottomSheetCallback(
            object : BottomSheetBehavior.BottomSheetCallback() {

                override fun onSlide(bottomSheet: View, slideOffset: Float) {
                    updateBottomButtons()
                }

                override fun onStateChanged(bottomSheet: View, newState: Int) {
                    updateBottomButtons()
                }
            },
        )

        binding.titleLayout.checkHeightThen {
            binding.categoryRecyclerView.updateLayoutParams<ConstraintLayout.LayoutParams> {
                val fullHeight = activity.window.decorView.height
                val insets = activity.window.decorView.rootWindowInsetsCompat
                matchConstraintMaxHeight =
                    fullHeight - (insets?.getInsets(systemBars())?.top ?: 0) -
                    binding.titleLayout.height - binding.buttonLayout.height - 45.dpToPx
            }
        }

        fastAdapter = FastAdapter.with(itemAdapter)
        fastAdapter.setHasStableIds(true)
        binding.categoryRecyclerView.layoutManager = LinearLayoutManager(context)
        binding.categoryRecyclerView.adapter = fastAdapter
        itemAdapter.set(
            categories.mapIndexed { index, category ->
                AddNovelCategoryItem(category).apply {
                    skipInversed = preselected[index] != TriStateCheckBox.State.IGNORE
                    state = preselected[index]
                }
            },
        )
        setCategoriesButtons()
        fastAdapter.onClickListener = onClickListener@{ view, _, item, _ ->
            val checkBox = view as? TriStateCheckBox ?: return@onClickListener true
            checkBox.goToNextStep()
            item.state = checkBox.state
            setCategoriesButtons()
            true
        }
    }

    private fun setCategoriesButtons() {
        val addingMore = checkedItems.isNotEmpty() &&
            selectedCategories.isNotEmpty() &&
            selectedItems.map { it.category }
                .containsAll(selectedCategories) &&
            checkedItems.size > preCheckedCategories.size
        val nothingChanged = itemAdapter.adapterItems.map { it.state }
            .toTypedArray()
            .contentEquals(preselected)
        val removing = selectedItems.isNotEmpty() && (
            (
                selectedCategories.containsAll(indeterminateItems.map { it.category }) &&
                    preIndeterminateCategories.size > indeterminateItems.size
                ) ||
                (
                    preCheckedCategories.containsAll(checkedItems.map { it.category }) &&
                        preCheckedCategories.size > checkedItems.size
                    )
            ) &&
            preCheckedCategories.size >= checkedItems.size &&
            preIndeterminateCategories.size >= indeterminateItems.size

        val items = when {
            addingToLibrary -> checkedItems.map { it.category }
            addingMore -> checkedItems.map { it.category }.subtract(preCheckedCategories.toSet())
            removing -> selectedCategories.subtract(selectedItems.map { it.category }.toSet())
            nothingChanged -> selectedItems.map { it.category }
            else -> checkedItems.map { it.category }
        }
        binding.addToCategoriesButton.text = context.getString(
            when {
                addingToLibrary || (addingMore && !nothingChanged) -> MR.strings.add_to_
                removing -> MR.strings.remove_from_
                nothingChanged -> MR.strings.keep_in_
                else -> MR.strings.move_to_
            },
            when (items.size) {
                0 -> context.getString(MR.strings.default_category).lowercase(Locale.ROOT)
                1 -> items.firstOrNull()?.name ?: ""
                else -> context.getString(
                    MR.plurals.category_plural,
                    items.size,
                    items.size,
                )
            },
        )
    }

    override fun onStart() {
        super.onStart()
        sheetBehavior.expand()
        sheetBehavior.skipCollapsed = true
        updateBottomButtons()
        binding.root.post {
            binding.categoryRecyclerView.scrollToPosition(
                max(0, itemAdapter.adapterItems.indexOf(selectedItems.firstOrNull())),
            )
        }
    }

    fun updateBottomButtons() {
        val bottomSheet = binding.root.parent as? View ?: return
        val bottomSheetVisibleHeight = -bottomSheet.top + (activity.window.decorView.height - bottomSheet.height)
        binding.buttonLayout.translationY = bottomSheetVisibleHeight.toFloat()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val headerHeight = (activity as? MainActivity)?.toolbarHeight ?: 0
        binding.buttonLayout.updatePaddingRelative(
            bottom = activity.window.decorView.rootWindowInsetsCompat
                ?.getInsets(systemBars())?.bottom ?: 0,
        )

        binding.buttonLayout.updateLayoutParams<ConstraintLayout.LayoutParams> {
            height = headerHeight + binding.buttonLayout.paddingBottom
        }

        binding.cancelButton.setOnClickListener { dismiss() }
        // Inline new-category creation via the novel-side ManageNovelCategoryDialog. Reuses the
        // same dialog the manga sheet uses, just over the novel data layer. After commit, refresh
        // the category list so the new entry is selectable without dismissing the sheet.
        binding.newCategoryButton.setOnClickListener {
            ManageNovelCategoryDialog(null) {
                // FIXME: Don't do blocking (mirrors the manga sheet's same FIXME)
                categories = runBlocking { getNovelCategories.await() }.toMutableList()
                val map = itemAdapter.adapterItems.associate { it.category.id to it.state }
                itemAdapter.set(
                    categories.mapIndexed { index, category ->
                        AddNovelCategoryItem(category).apply {
                            skipInversed =
                                preselected.getOrElse(index) { TriStateCheckBox.State.UNCHECKED } != TriStateCheckBox.State.IGNORE
                            state = map[category.id] ?: TriStateCheckBox.State.CHECKED
                        }
                    },
                )
                setCategoriesButtons()
            }.show(activity)
        }

        binding.addToCategoriesButton.setOnClickListener {
            binding.addToCategoriesButton.isEnabled = false
            val owner = activity as? LifecycleOwner
            if (owner == null) {
                runBlocking { addNovelsToCategories() }
                dismiss()
                return@setOnClickListener
            }
            owner.lifecycleScope.launchIO {
                try {
                    addNovelsToCategories()
                    withUIContext { dismiss() }
                } catch (e: Throwable) {
                    withUIContext { binding.addToCategoriesButton.isEnabled = true }
                    throw e
                }
            }
        }
    }

    private suspend fun addNovelsToCategories() {
        // Novel side has no favorite-flip path here: the action-mode flow that drives this sheet
        // only operates on already-favourited library novels, and an `UpdateNovel(favorite=...)`
        // interactor doesn't exist yet (the Novel data class's `favorite` field is val). If a
        // future "Add to library" entry needs this, mirror SetCategoriesSheet's `toFavorite`
        // loop with an UpdateNovel interactor.

        val addCategories = checkedItems.map(AddNovelCategoryItem::category)
        val removeCategories = uncheckedItems.map(AddNovelCategoryItem::category)
        // Remember the chosen categories so the "Last used" default-category option can reuse them on
        // the next add-to-library (mirrors the manga SetCategoriesSheet).
        NovelCategory.lastCategoriesAddedTo = addCategories.mapNotNull { it.id }.toSet()
        // Parallel per-novel existing-categories fetch, matching the manga sheet's anti-ANR
        // shape (sequential awaitByNovelId calls would N×DB round-trip on bulk-add).
        val perNovel = coroutineScope {
            listNovel.map { novel ->
                async { novel to getNovelCategories.awaitByNovelId(novel.id!!) }
            }.awaitAll()
        }
        val novelCategories = perNovel.flatMap { (novel, existing) ->
            existing.subtract(removeCategories.toSet())
                .plus(addCategories)
                .distinct()
                .map { NovelInCategory.create(novel, it) }
        }
        setNovelCategories.awaitAll(listNovel.mapNotNull { it.id }, novelCategories)
        withUIContext { onNovelsAdded() }
    }
}
