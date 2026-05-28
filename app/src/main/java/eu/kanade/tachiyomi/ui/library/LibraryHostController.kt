package eu.kanade.tachiyomi.ui.library

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import eu.kanade.tachiyomi.ui.base.controller.BaseComposeController
import com.google.android.material.tabs.TabLayout
import eu.kanade.tachiyomi.databinding.LibraryHostControllerBinding
import eu.kanade.tachiyomi.ui.base.controller.BaseLegacyController
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.ui.main.BottomSheetController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import eu.kanade.tachiyomi.ui.main.TabbedInterface
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.compatToolTipText
import eu.kanade.tachiyomi.util.view.setAppBarBG
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Bottom-nav root for the legacy library path. Installs a Manga / Novels tab strip in the
 * activity's `mainTabs` (the same shape Browse / Recents / [eu.kanade.tachiyomi.ui.category.LibraryCategoriesHostController]
 * use) and swaps a child controller per tab:
 *  - Manga → existing [LibraryController] (unchanged, the legacy XML manga library).
 *  - Novels → [NovelLibraryComposeController] (hosts the same `NovelLibraryTabContent` the
 *    Compose-library path renders).
 *
 * Replaces `LibraryController` as the bottom-nav root in [MainActivity] when
 * `composeLibrary` is OFF, so users on the legacy library can still reach their novel library
 * without porting the novel side to XML.
 *
 * Implements [TabbedInterface] so the appbar's `useTabsInPreLayout` and the parent-walking
 * `fullAppBarHeight` give the hosted manga recycler the correct top inset.
 *
 * Implements the legacy root-controller contracts ([BottomSheetController], [RootSearchInterface],
 * [FloatingSearchInterface]) by delegating to whichever child is active, because [MainActivity]
 * polls those on the root, not recursively on the child router. Options menu inflation is
 * handled by Conductor itself: when an attached child sets `hasOptionsMenu = true`, the parent
 * router walks child routers when building the menu, so the manga library's menu items reach
 * the toolbar without manual forwarding here.
 */
class LibraryHostController(bundle: Bundle? = null) :
    BaseLegacyController<LibraryHostControllerBinding>(bundle),
    TabbedInterface,
    BottomSheetController,
    RootSearchInterface,
    FloatingSearchInterface {

    constructor(initialTab: Int) : this(
        Bundle().apply { putInt(INITIAL_TAB, initialTab) },
    )

    private val initialTab: Int get() = args.getInt(INITIAL_TAB, TAB_MANGA)

    override fun createBinding(inflater: LayoutInflater) =
        LibraryHostControllerBinding.inflate(inflater)

    // MainActivity reads getTitle / getSearchTitle off the top of the main router (which is
    // now us) to populate the searchToolbar title at MainActivity.kt:794-805. Delegate so the
    // active child's "Library" title + 'Search "<random library entry>"' label still show.
    override fun getTitle(): String? = (currentChild() as? BaseLegacyController<*>)?.getTitle()

    override fun getSearchTitle(): String? = (currentChild() as? BaseLegacyController<*>)?.getSearchTitle()

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        // Install tabs + the initial child before the first onChangeStarted gate runs, matching
        // the BrowseController / LibraryCategoriesHostController pattern (commit b76bc04146
        // showed that deferring tab setup to onChangeStarted loses to the outgoing controller's
        // hide animation on cold start).
        setupTabs()
        ensureChildController(initialTab)
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type.isEnter) {
            if (type == ControllerChangeType.POP_ENTER) {
                // Returning from a deeper push (e.g. MangaDetailsController dismissed). The
                // previous controller's PUSH_EXIT cleared mainTabs, so reinstall ours.
                setupTabs()
                // super.onChangeStarted called setAppBarVisibility() on us (showLegacyAppBar
                // because we're a BaseLegacyController). If the active child is a Compose
                // controller that expects the legacy bar hidden, re-apply its visibility now.
                // The child itself doesn't re-fire onChangeStarted on the host's pop-enter.
                (currentChild() as? BaseComposeController)?.setAppBarVisibility()
                // Bridge missing on-enter bar reset to the manga child. Conductor only fires
                // lifecycle events on the controller whose RouterTransaction is active — that's
                // us, not the child — so the child's `scrollViewWith` listener at
                // ControllerExtensions.kt:438-455 never runs on this pop-back. Replicate the
                // full enter-block it would have done so the bar re-anchors against the
                // recycler's current scroll offset instead of staying at MangaDetails' last Y,
                // and the toolbar mode + tab-pre-layout flags get re-asserted (MangaDetails ran
                // through setToolbarModeBy with its own controller, leaving the bar in a state
                // tailored to MangaDetails rather than the manga library).
                (currentChild() as? LibraryController)?.let { child ->
                    val recycler = child.binding.libraryGridRecycler.recycler
                    // Re-show the floating search toolbar (with the search card + subtitle) the
                    // way MainActivity does when transitioning to a FloatingSearchInterface
                    // controller. Must happen BEFORE the background-color set below: this call
                    // internally runs `binding.appBar.background = null` (MainActivity.kt:829)
                    // which would wipe our color if it ran after.
                    (activity as? MainActivity)?.setFloatingToolbar(show = true, showSearchAnyway = true)
                    activityBinding?.appBar?.let { appBar ->
                        appBar.lockYPos = false
                        appBar.y = 0f
                        appBar.hideBigView(useSmall = false)
                        appBar.setToolbarModeBy(child)
                        appBar.useTabsInPreLayout = true
                        appBar.updateAppBarAfterY(recycler)
                    }
                    // Re-evaluate the bar's background color the way `scrollViewWith`'s
                    // onScrolled does on every scroll event (ControllerExtensions.kt:570).
                    // MangaDetails leaves the bar background at whatever color it last
                    // animated to, and the child's scroll listener doesn't fire on a
                    // parent-router pop-back to fix it. `setAppBarBG` blends colorSurface ↔
                    // colorPrimaryVariant for the tabbed-library case (includeTabView=true),
                    // matching the purple the user sees during normal scrolling. Done after
                    // setFloatingToolbar so its `binding.appBar.background = null` doesn't
                    // wipe our color.
                    val atTop = !recycler.canScrollVertically(-1)
                    child.setAppBarBG(if (atTop) 0f else 1f, includeTabView = true)
                }
            }
        } else {
            // Leaving. Hide the tab bar unless the next controller is also tabbed and will
            // install its own (matches the Browse / Recents skip-on-TabbedInterface pattern so
            // setRoot from one TabbedInterface to another doesn't strand mainTabs empty).
            val nextController = router.backstack.lastOrNull()?.controller
            if (nextController !is TabbedInterface && nextController !is DialogController) {
                (activity as? MainActivity)?.showTabBar(show = false, animate = true)
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
     * router tag so a process kill / restore reuses the same router; `setRoot` (not push) so
     * back-press leaves the host instead of cycling tabs.
     */
    private fun ensureChildController(position: Int) {
        if (!isBindingInitialized) return
        val childRouter = getChildRouter(binding.libraryContainer, CHILD_ROUTER_TAG)
        installTitleSyncListener(childRouter)
        val current = childRouter.backstack.lastOrNull()?.controller
        when (position) {
            TAB_MANGA -> {
                if (current is LibraryController) return
                childRouter.setRoot(RouterTransaction.with(LibraryController()))
            }
            TAB_NOVELS -> {
                if (current is NovelLibraryComposeController) return
                childRouter.setRoot(RouterTransaction.with(NovelLibraryComposeController()))
            }
        }
    }

    private var titleSyncInstalled = false

    /**
     * Re-fire the host's [setTitle] whenever a child controller-change completes so the
     * activity toolbar title and the searchToolbar's 'Search "..."' label update on tab swap.
     * Without this, BaseLegacyController's setTitle on the child is short-circuited by the
     * host's non-null getTitle and the host itself doesn't re-enter on a child swap, leaving
     * the toolbar with whatever was set at host attach (or empty if the child hadn't attached
     * yet at that point).
     */
    private fun installTitleSyncListener(childRouter: Router) {
        if (titleSyncInstalled) return
        titleSyncInstalled = true
        childRouter.addChangeListener(object : com.bluelinelabs.conductor.ControllerChangeHandler.ControllerChangeListener {
            override fun onChangeStarted(
                to: Controller?,
                from: Controller?,
                isPush: Boolean,
                container: android.view.ViewGroup,
                handler: ControllerChangeHandler,
            ) {}

            override fun onChangeCompleted(
                to: Controller?,
                from: Controller?,
                isPush: Boolean,
                container: android.view.ViewGroup,
                handler: ControllerChangeHandler,
            ) {
                setTitle()
            }
        })
    }

    private fun currentChild(): Controller? {
        // Called from MainActivity.canShowFloatingToolbar via showFloatingBar() / from the
        // touch-event forwarder via currentLibraryController(). Both fire on controller-change
        // events that can land before onViewCreated, so the lateinit `binding` may not be
        // initialised yet. Mirror the guard in ensureChildController.
        if (!isBindingInitialized) return null
        return getChildRouter(binding.libraryContainer, CHILD_ROUTER_TAG)
            .backstack.lastOrNull()?.controller
    }

    /**
     * Used by [MainActivity] to forward category-swipe touch events
     * ([LibraryController.handleGeneralEvent]) to the active manga child when the legacy
     * library is the bottom-nav root.
     */
    fun currentLibraryController(): LibraryController? = currentChild() as? LibraryController

    /**
     * Programmatic tab switch. Called from the Compose-side tab row inside
     * [NovelLibraryComposeController] when the user taps the Manga tab there (the legacy
     * mainTabs strip is hidden while the Compose child is active). Selecting the mainTabs
     * tab fires the tab listener which calls [ensureChildController].
     */
    fun selectTab(position: Int) {
        val tabs = activityBinding?.mainTabs
        val tab = tabs?.getTabAt(position)
        if (tab != null && tab.position != tabs.selectedTabPosition) {
            tab.select()
        } else {
            ensureChildController(position)
        }
    }

    // BottomSheetController: MainActivity routes the long-tap-nav and bottom-nav-reselect
    // triggers to the root controller (MainActivity.kt:413, 438, 556, 1626, 1635). Delegate so
    // the manga child's FilterBottomSheet still toggles. The novel child has no equivalent
    // sheet; its branch ends in a no-op.
    override fun showSheet() {
        (currentChild() as? BottomSheetController)?.showSheet()
    }
    override fun hideSheet() {
        (currentChild() as? BottomSheetController)?.hideSheet()
    }
    override fun toggleSheet() {
        (currentChild() as? BottomSheetController)?.toggleSheet()
    }

    // FloatingSearchInterface: default true if the active child doesn't override. Both children
    // currently use the default, so this is effectively a no-op delegation; kept explicit so a
    // future per-child override propagates.
    override fun showFloatingBar(): Boolean {
        return (currentChild() as? FloatingSearchInterface)?.showFloatingBar() ?: true
    }

    // Menu click forwarding. Conductor walks child routers automatically when building the
    // menu (so the manga child's R.menu.library entries appear), but `onOptionsItemSelected`
    // is NOT auto-forwarded. MainActivity wires the searchToolbar at MainActivity.kt:634 via
    // `router.backstack.lastOrNull()?.controller?.onOptionsItemSelected(it)`, which now lands
    // on the host instead of the manga child — so taps on Filter / Display options / etc. in
    // the compact search toolbar were getting lost. Delegate to the child so those clicks
    // reach LibraryController.onOptionsItemSelected again. onPrepareOptionsMenu is forwarded
    // for the same reason (per-item visibility / state).
    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        currentChild()?.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return currentChild()?.onOptionsItemSelected(item) == true || super.onOptionsItemSelected(item)
    }

    companion object {
        const val TAB_MANGA = 0
        const val TAB_NOVELS = 1

        private const val INITIAL_TAB = "library_host:initial_tab"
        private const val CHILD_ROUTER_TAG = "library_host_child_router"
    }
}
