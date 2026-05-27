package eu.kanade.tachiyomi.ui.category

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import com.bluelinelabs.conductor.RouterTransaction
import com.google.android.material.tabs.TabLayout
import eu.kanade.tachiyomi.databinding.LibraryCategoriesHostControllerBinding
import eu.kanade.tachiyomi.ui.base.SmallToolbarInterface
import eu.kanade.tachiyomi.ui.base.controller.BaseLegacyController
import eu.kanade.tachiyomi.ui.category.novel.NovelCategoryController
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Tabbed host for the library Edit Categories screen. The top tab row toggles between the
 * manga [CategoryController] and the novel [NovelCategoryController]; only one is attached at
 * a time via the host's child router. Initial tab is supplied via bundle arg [INITIAL_TAB],
 * so the entry point (Settings -> Library -> Edit categories, or the library Display sheet's
 * Edit/Add button) can open the host on whichever tab matches the user's context.
 *
 * Both child controllers run their existing FlexibleAdapter stacks unmodified, so manga users
 * keep drag-and-drop, multi-select delete, ActionMode, inline rename, and the create row; the
 * novel side carries the same feature set against the novel data layer.
 */
class LibraryCategoriesHostController(bundle: Bundle? = null) :
    BaseLegacyController<LibraryCategoriesHostControllerBinding>(bundle),
    SmallToolbarInterface {

    constructor(initialTab: Int) : this(
        Bundle().apply { putInt(INITIAL_TAB, initialTab) },
    )

    private val initialTab: Int get() = args.getInt(INITIAL_TAB, TAB_MANGA)

    override fun getTitle(): String? {
        return view?.context?.getString(MR.strings.edit_categories)
    }

    override fun createBinding(inflater: LayoutInflater) =
        LibraryCategoriesHostControllerBinding.inflate(inflater)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)

        binding.tabLayout.removeAllTabs()
        binding.tabLayout.addTab(
            binding.tabLayout.newTab().setText(view.context.getString(MR.strings.manga)),
            initialTab == TAB_MANGA,
        )
        binding.tabLayout.addTab(
            binding.tabLayout.newTab().setText(view.context.getString(MR.strings.light_novels)),
            initialTab == TAB_NOVELS,
        )

        ensureChildController(initialTab)

        binding.tabLayout.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    val position = tab?.position ?: return
                    ensureChildController(position)
                }
                override fun onTabUnselected(tab: TabLayout.Tab?) {}
                override fun onTabReselected(tab: TabLayout.Tab?) {}
            },
        )
    }

    /**
     * Swap the currently-attached child controller to match [position]. Uses a stable child
     * router tag so the child controller's view state survives config changes within the host.
     * setRoot replaces the child without keeping a back stack — back-press from a child should
     * pop the host as a whole, not switch tabs.
     */
    private fun ensureChildController(position: Int) {
        val childRouter = getChildRouter(binding.categoriesContainer, CHILD_ROUTER_TAG)
        val current = childRouter.backstack.lastOrNull()?.controller
        when (position) {
            TAB_MANGA -> {
                if (current is CategoryController) return
                childRouter.setRoot(RouterTransaction.with(CategoryController()))
            }
            TAB_NOVELS -> {
                if (current is NovelCategoryController) return
                childRouter.setRoot(RouterTransaction.with(NovelCategoryController()))
            }
        }
    }

    companion object {
        const val TAB_MANGA = 0
        const val TAB_NOVELS = 1

        private const val INITIAL_TAB = "library_categories_host:initial_tab"
        private const val CHILD_ROUTER_TAG = "library_categories_host_child_router"
    }
}
