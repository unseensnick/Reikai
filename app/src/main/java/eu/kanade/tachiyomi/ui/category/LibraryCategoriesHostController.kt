package eu.kanade.tachiyomi.ui.category

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.bluelinelabs.conductor.RouterTransaction
import com.google.android.material.tabs.TabLayout
import eu.kanade.tachiyomi.databinding.LibraryCategoriesHostControllerBinding
import eu.kanade.tachiyomi.ui.base.SmallToolbarInterface
import eu.kanade.tachiyomi.ui.base.controller.BaseLegacyController
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.category.novel.NovelCategoryController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.TabbedInterface
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.compatToolTipText
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Tabbed host for the library Edit Categories screen. Tabs live in the activity's
 * `mainTabs` strip (the same place Browse and Recents put theirs) so the hosted
 * [CategoryController] and [NovelCategoryController] keep their own `liftAppbarWith`
 * insets without double-padding: the child's recycler gets pushed below `toolbar + tabs`
 * the same way the manga library's recycler does today.
 *
 * Tab selection swaps the child controller via the host's child router; `setRoot` (not
 * `pushController`) is used so back-press leaves the host rather than just changing tabs.
 *
 * Implements [TabbedInterface] so peers that hide `mainTabs` on exit (Browse / Recents)
 * skip the hide when transitioning to this host.
 */
class LibraryCategoriesHostController(bundle: Bundle? = null) :
    BaseLegacyController<LibraryCategoriesHostControllerBinding>(bundle),
    SmallToolbarInterface,
    TabbedInterface {

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
        // Install tabs and the initial child here so cold-start PUSH_ENTER works (matches the
        // BrowseController fix where the same setup ran too late inside onChangeStarted's
        // isControllerVisible gate and left mainTabs empty).
        setupTabs()
        ensureChildController(initialTab)
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type.isEnter) {
            if (type == ControllerChangeType.POP_ENTER) {
                // Returning from a deeper push (e.g. ManageNovelCategoryDialog dismissed): the
                // previous controller's PUSH_EXIT cleared mainTabs, so reinstall ours.
                setupTabs()
            }
        } else {
            // Leaving. Hide the tab bar unless the next controller is also tabbed and will
            // install its own (matches the Browse / Recents skip-on-TabbedInterface pattern).
            val nextController = router.backstack.lastOrNull()?.controller
            if (nextController !is TabbedInterface && nextController !is DialogController) {
                (activity as? MainActivity)?.showTabBar(
                    show = false,
                    animate = nextController !is SmallToolbarInterface,
                )
            }
        }
    }

    private fun setupTabs() {
        val tabs = activityBinding?.mainTabs ?: return
        tabs.removeAllTabs()
        tabs.clearOnTabSelectedListeners()
        val mangaTab = tabs.newTab().setText(view?.context?.getString(MR.strings.manga)).also {
            it.view.compatToolTipText = null
        }
        val novelTab = tabs.newTab().setText(view?.context?.getString(MR.strings.light_novels)).also {
            it.view.compatToolTipText = null
        }
        tabs.addTab(mangaTab, initialTab == TAB_MANGA)
        tabs.addTab(novelTab, initialTab == TAB_NOVELS)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                ensureChildController(tab?.position ?: TAB_MANGA)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        (activity as? MainActivity)?.showTabBar(true)
    }

    /**
     * Swap the currently-attached child controller to match [position]. Uses a stable child
     * router tag so the child controller's view state survives config changes within the host.
     */
    private fun ensureChildController(position: Int) {
        if (!isBindingInitialized) return
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
