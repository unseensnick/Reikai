package eu.kanade.tachiyomi.ui.source

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.os.Build
import android.os.Parcelable
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.activity.BackEventCompat
import androidx.appcompat.widget.SearchView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import androidx.core.graphics.ColorUtils
import androidx.core.view.ScrollingView
import androidx.core.view.doOnNextLayout
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.RecyclerView
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.snackbar.Snackbar
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.preference.PreferenceValues
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.BrowseControllerBinding
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.LocalSource
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.base.controller.BaseLegacyController
import eu.kanade.tachiyomi.ui.extension.ExtensionFilterController
import com.google.android.material.tabs.TabLayout
import eu.kanade.tachiyomi.ui.main.BottomSheetController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.base.SmallToolbarInterface
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import eu.kanade.tachiyomi.ui.main.TabbedInterface
import eu.kanade.tachiyomi.ui.setting.controllers.SettingsBrowseController
import eu.kanade.tachiyomi.ui.setting.controllers.SettingsSourcesController
import eu.kanade.tachiyomi.ui.source.browse.BrowseSourceController
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getBottomGestureInsets
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.system.spToPx
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.checkHeightThen
import eu.kanade.tachiyomi.util.view.compatToolTipText
import eu.kanade.tachiyomi.util.view.collapse
import eu.kanade.tachiyomi.util.view.expand
import eu.kanade.tachiyomi.util.view.isCollapsed
import eu.kanade.tachiyomi.util.view.isCompose
import eu.kanade.tachiyomi.util.view.isControllerVisible
import eu.kanade.tachiyomi.util.view.onAnimationsFinished
import eu.kanade.tachiyomi.util.view.scrollViewWith
import eu.kanade.tachiyomi.util.view.setAction
import eu.kanade.tachiyomi.util.view.setAppBarBG
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.snack
import eu.kanade.tachiyomi.util.view.toolbarHeight
import eu.kanade.tachiyomi.util.view.updateGradiantBGRadius
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import eu.kanade.tachiyomi.widget.LinearLayoutManagerAccurateOffset
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.parcelize.Parcelize
import uy.kohesive.injekt.injectLazy
import yokai.domain.base.BasePreferences
import yokai.domain.base.BasePreferences.ExtensionInstaller
import yokai.i18n.MR
import yokai.presentation.extension.repo.ExtensionRepoController
import yokai.util.lang.getString
import java.util.*
import kotlin.math.max

/**
 * This controller shows and manages the different catalogues enabled by the user.
 * This controller should only handle UI actions, IO actions should be done by [SourcePresenter]
 * [SourceAdapter.SourceListener] call function data on browse item click.
 */
class BrowseController :
    BaseLegacyController<BrowseControllerBinding>(),
    FlexibleAdapter.OnItemClickListener,
    SourceAdapter.SourceListener,
    RootSearchInterface,
    FloatingSearchInterface,
    BottomSheetController,
    TabbedInterface,
    KoinComponent {

    private val basePreferences: BasePreferences by injectLazy()

    /**
     * Application preferences.
     */
    private val preferences: PreferencesHelper by injectLazy()
    private val novelPreferences: yokai.domain.novel.NovelPreferences by inject()

    /**
     * Adapter containing sources.
     */
    private var adapter: SourceAdapter? = null

    private val _extQuery = MutableStateFlow("")

    /** Search query shared across the Extensions sheet tabs (Manga ext, LN plugins, Migration).
     *  Subscribed by the LN Compose tab; read synchronously by manga ext + migration filters. */
    val extQuery: StateFlow<String> = _extQuery.asStateFlow()

    /** Top + bottom padding (raw px) for the LN ComposeView, kept in sync with the manga RV's
     *  insets via the same `scrollViewWith.afterInsets` callback. The ComposeView itself stays
     *  un-padded so the LazyColumn can scroll its items through the appBar area as the bar
     *  collapses, matching the manga RV's clipToPadding=false behavior. */
    private val lnContentPaddingPx = MutableStateFlow(0 to 0)

    private val _lnSourcesSearchQuery = MutableStateFlow("")

    /** Search query for the main Browse view's Light novel sources sub-tab. Live-filters the
     *  LN sources list by name on every keystroke; submit on this tab is a no-op (the manga
     *  side's submit-to-global-search behavior stays on the manga side). */
    val lnSourcesSearchQuery: StateFlow<String> = _lnSourcesSearchQuery.asStateFlow()

    var headerHeight = 0

    var showingExtensions = false

    var snackbar: Snackbar? = null

    private var ogRadius = 0f
    private var deviceRadius = 0f to 0f
    private var lastScale = 1f

    override val mainRecycler: RecyclerView
        get() = binding.sourceRecycler

    /**
     * Called when controller is initialized.
     */
    init {
        setHasOptionsMenu(true)
    }

    override fun getTitle(): String? = view?.context?.getString(MR.strings.browse)

    override fun getSearchTitle(): String? {
        return searchTitle(view?.context?.getString(MR.strings.sources)?.lowercase(Locale.ROOT))
    }

    val presenter = SourcePresenter(this)

    override fun createBinding(inflater: LayoutInflater) = BrowseControllerBinding.inflate(inflater)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        val isReturning = adapter != null
        adapter = SourceAdapter(this)
        // Create binding.sourceRecycler and set adapter.
        binding.sourceRecycler.layoutManager = LinearLayoutManagerAccurateOffset(view.context)

        binding.sourceRecycler.adapter = adapter
        binding.sourceRecycler.onAnimationsFinished {
            (activity as? MainActivity)?.splashState?.ready = true
        }
        adapter?.isSwipeEnabled = true
        adapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        scrollViewWith(
            binding.sourceRecycler,
            afterInsets = {
                headerHeight = binding.sourceRecycler.paddingTop
                val bottomPad = (activityBinding?.bottomNav?.height ?: it.getBottomGestureInsets()) + 58.spToPx
                binding.sourceRecycler.updatePaddingRelative(bottom = bottomPad)
                // Push the top + bottom insets into the LN ComposeView's LazyColumn via
                // Compose contentPadding, NOT via android:padding on the ComposeView. With
                // android padding, the LazyColumn sits below the padded area and items can't
                // scroll into it as the appBar collapses, leaving visible dead space when the
                // bar moves up. Compose contentPadding behaves like clipToPadding=false:
                // items scroll through the padded region visually.
                lnContentPaddingPx.value = headerHeight to bottomPad
                if (activityBinding?.bottomNav == null) {
                    setBottomPadding()
                }
                deviceRadius = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val wInsets = it.toWindowInsets()
                    val lCorner = wInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
                    val rCorner = wInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
                    (lCorner?.radius?.toFloat() ?: 0f) to (rCorner?.radius?.toFloat() ?: 0f)
                } else {
                    ogRadius to ogRadius
                }
            },
            onBottomNavUpdate = {
                setBottomPadding()
            },
        )
        if (!isReturning) {
            activityBinding?.appBar?.lockYPos = true
        }
        binding.sourceRecycler.post {
            setBottomSheetTabs(if (binding.bottomSheet.root.sheetBehavior.isCollapsed()) 0f else 1f)
            binding.sourceRecycler.updatePaddingRelative(
                bottom = (activityBinding?.bottomNav?.height ?: 0) + 58.spToPx,
            )
            updateTitleAndMenu()
        }

        binding.bottomSheet.root.onCreate(this)

        basePreferences.extensionInstaller().changes()
            .drop(1)
            .onEach {
                binding.bottomSheet.root.setCanInstallPrivately(it == ExtensionInstaller.PRIVATE)
            }
            .launchIn(viewScope)

        binding.bottomSheet.root.sheetBehavior?.isGestureInsetBottomIgnored = true

        binding.bottomSheet.root.sheetBehavior?.addBottomSheetCallback(
            object : BottomSheetBehavior
            .BottomSheetCallback() {
                override fun onSlide(bottomSheet: View, progress: Float) {
                    val oldShow = showingExtensions
                    showingExtensions = progress > 0.92f
                    if (oldShow != showingExtensions) {
                        updateTitleAndMenu()
                        (activity as? MainActivity)?.reEnableBackPressedCallBack()
                    }
                    binding.bottomSheet.root.apply {
                        if (lastScale != 1f && scaleY != 1f) {
                            val scaleProgress = ((1f - progress) * (1f - lastScale)) + lastScale
                            scaleX = scaleProgress
                            scaleY = scaleProgress
                            for (i in 0 until childCount) {
                                val childView = getChildAt(i)
                                childView.scaleY = scaleProgress
                            }
                        }
                    }
                    binding.bottomSheet.sheetToolbar.isVisible = true
                    setBottomSheetTabs(max(0f, progress))
                }

                override fun onStateChanged(p0: View, state: Int) {
                    if (state == BottomSheetBehavior.STATE_SETTLING) {
                        binding.bottomSheet.root.updatedNestedRecyclers()
                    } else if (state == BottomSheetBehavior.STATE_EXPANDED && binding.bottomSheet.root.isExpanding) {
                        binding.bottomSheet.root.updatedNestedRecyclers()
                        binding.bottomSheet.root.isExpanding = false
                    }

                    binding.bottomSheet.root.apply {
                        if ((
                            state == BottomSheetBehavior.STATE_COLLAPSED ||
                                state == BottomSheetBehavior.STATE_EXPANDED ||
                                state == BottomSheetBehavior.STATE_HIDDEN
                            ) &&
                            scaleY != 1f
                        ) {
                            scaleX = 1f
                            scaleY = 1f
                            pivotY = 0f
                            translationX = 0f
                            for (i in 0 until childCount) {
                                val childView = getChildAt(i)
                                childView.scaleY = 1f
                            }
                            lastScale = 1f
                        }
                    }

                    val extBottomSheet = binding.bottomSheet.root
                    if (state == BottomSheetBehavior.STATE_EXPANDED ||
                        state == BottomSheetBehavior.STATE_COLLAPSED
                    ) {
                        binding.bottomSheet.root.sheetBehavior?.isDraggable = true
                        showingExtensions = state == BottomSheetBehavior.STATE_EXPANDED
                        binding.bottomSheet.sheetToolbar.isVisible = showingExtensions
                        updateTitleAndMenu()
                        if (state == BottomSheetBehavior.STATE_EXPANDED) {
                            extBottomSheet.fetchOnlineExtensionsIfNeeded()
                        } else {
                            extBottomSheet.shouldCallApi = true
                        }
                    }

                    retainViewMode = if (state == BottomSheetBehavior.STATE_EXPANDED) {
                        RetainViewMode.RETAIN_DETACH
                    } else {
                        RetainViewMode.RELEASE_DETACH
                    }
                    binding.bottomSheet.sheetLayout.isClickable = state == BottomSheetBehavior.STATE_COLLAPSED
                    binding.bottomSheet.sheetLayout.isFocusable = state == BottomSheetBehavior.STATE_COLLAPSED
                    if (state == BottomSheetBehavior.STATE_COLLAPSED || state == BottomSheetBehavior.STATE_EXPANDED) {
                        setBottomSheetTabs(if (state == BottomSheetBehavior.STATE_COLLAPSED) 0f else 1f)
                    }
                }
            },
        )

        if (showingExtensions) {
            binding.bottomSheet.root.sheetBehavior?.expand()
        }
        ogRadius = view.resources.getDimension(R.dimen.rounded_radius)

        setSheetToolbar()
        presenter.onCreate()
        if (presenter.sourceItems.isNotEmpty()) {
            setSources(presenter.sourceItems, presenter.lastUsedItem)
        } else {
            binding.sourceRecycler.checkHeightThen {
                binding.sourceRecycler.scrollToPosition(0)
            }
        }
        // Install the source-type tab row here rather than waiting for onChangeStarted's
        // isEnter branch. On a cold-start PUSH_ENTER via setRoot the onChangeStarted gate
        // (`isControllerVisible`) doesn't fire reliably, leaving mainTabs empty + the
        // tabsFrameLayout gone. That also makes ExpandedAppBarLayout's realHeight overshoot
        // by 48dp (TabbedInterface flips useTabsInPreLayout on while the row isn't actually
        // rendered), letting the bar translate behind the status bar on scroll.
        setupSourceTypeTabs()
    }

    /**
     * Phase 8 follow-up CR8 (revised): mount Manga sources / Light novel sources as tabs in the
     * activity's mainTabs (via [TabbedInterface]) so they live IN the app bar and scroll with
     * its collapse behavior — same pattern as [eu.kanade.tachiyomi.ui.recents.RecentsController].
     *
     * The controller's view tree just owns the swap container (manga RV + LN ComposeView);
     * which one is visible is driven by the active main-tab position.
     */
    private fun setupSourceTypeTabs() {
        val tabs = activityBinding?.mainTabs ?: return
        tabs.removeAllTabs()
        tabs.clearOnTabSelectedListeners()
        val mangaTab = tabs.newTab().setText(view?.context?.getString(MR.strings.manga)).also {
            it.view.compatToolTipText = null
        }
        val novelTab = tabs.newTab().setText(view?.context?.getString(MR.strings.light_novels)).also {
            it.view.compatToolTipText = null
        }
        tabs.addTab(mangaTab, true)
        tabs.addTab(novelTab, false)
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab?) {
                applySourceTypeTab(tab?.position ?: 0)
            }
            override fun onTabUnselected(tab: TabLayout.Tab?) {}
            override fun onTabReselected(tab: TabLayout.Tab?) {}
        })
        (activity as? MainActivity)?.showTabBar(true)
        // Default to Manga on entry.
        applySourceTypeTab(0)
    }

    /**
     * Bridge the Compose LazyColumn's nested-scroll deltas (forwarded via
     * `LnSourceListContent.onScrollDelta`) into the activity app bar's collapse behavior,
     * mirroring `scrollViewWith.onScrolled` at ControllerExtensions.kt:512.
     *
     * We track the scroll offset locally ([lnTrackedOffset]) and expose it to the appBar via a
     * [ScrollingView] adapter so `updateAppBarAfterY` can run its full mode/alpha animation
     * logic (big-title fade, search-toolbar swap, compactSearchMode clamping). Without the
     * adapter, passing null would make the helper compute `translationY = -0 = 0` and undo any
     * manual translation; that's why the previous bypass-and-set-translationY-directly version
     * worked for sliding but not for the title fade.
     *
     * The Compose `atTop` flag is the authority for the "back at top" reset: when the
     * LazyColumn reports we're at offset 0, we reset [lnTrackedOffset] = 0 and let
     * `updateAppBarAfterY` snap translation accordingly.
     */
    private var lnTrackedOffset = 0

    private val lnScrollingViewAdapter = object : ScrollingView {
        override fun computeVerticalScrollOffset(): Int = lnTrackedOffset
        override fun computeVerticalScrollExtent(): Int = 0
        override fun computeVerticalScrollRange(): Int = Int.MAX_VALUE
        override fun computeHorizontalScrollOffset(): Int = 0
        override fun computeHorizontalScrollExtent(): Int = 0
        override fun computeHorizontalScrollRange(): Int = 0
    }

    private fun translateAppBarOnLnScroll(dy: Int, atTop: Boolean) {
        if (!isControllerVisible) return
        val appBar = activityBinding?.appBar ?: return
        val h = appBar.height
        if (h <= 0) return
        val bottomNav = activityBinding?.bottomNav
        if (atTop) {
            lnTrackedOffset = 0
            appBar.updateAppBarAfterY(lnScrollingViewAdapter, cancelAnim = false, inDragState = true)
            colorLnToolbar(false)
            if (bottomNav != null && bottomNav.translationY != 0f) {
                bottomNav.animate()
                    .translationY(0f)
                    .setDuration(150)
                    .setUpdateListener { setBottomPadding() }
                    .start()
            }
            return
        }
        if (dy == 0) return
        lnTrackedOffset = (lnTrackedOffset + dy).coerceAtLeast(0)
        appBar.updateAppBarAfterY(lnScrollingViewAdapter, cancelAnim = false, inDragState = true)
        colorLnToolbar(true)
        if (bottomNav != null && bottomNav.isVisible && preferences.hideBottomNavOnScroll().get()) {
            bottomNav.translationY = (bottomNav.translationY + dy)
                .coerceIn(0f, bottomNav.height.toFloat())
            setBottomPadding()
        }
    }

    /**
     * Mirrors `scrollViewWith.onScrollIdle` (ControllerExtensions.kt:573): when scrolling stops
     * mid-scroll, snaps the app bar and bottom nav to the nearer edge so they don't sit
     * half-collapsed. Updates [lnTrackedOffset] to the snap target so [updateAppBarAfterY]
     * sees a coherent offset on the next scroll event.
     */
    private fun onLnScrollIdle(atTop: Boolean) {
        if (!isControllerVisible) return
        val appBar = activityBinding?.appBar ?: return
        val h = appBar.height.toFloat()
        if (h <= 0f) return
        // Stay collapsed whenever the LazyColumn is scrolled past the top; only spring fully
        // open when we're actually at offset 0. Manga's `scrollViewWith` uses an |tY|>h/2
        // snap-to-edge threshold, but on the LN ComposeView a partial-collapse spring-back is
        // visually broken: items that scrolled under the bar stay scrolled (the LazyColumn's
        // contentPadding doesn't move with the bar's snap), so they're hidden behind the
        // re-expanded bar. Treat any scrolled state as "keep collapsed".
        val collapse = !atTop
        // Don't snap the bar on idle: any jump from where the drag left tY to a fixed target
        // is itself visible (a partial-collapse like tY=-45 jumping to the compact -428 reads
        // as "snapping up"). The drag-time clamp already keeps tY within the valid range, so
        // leaving tY alone matches the manga tab's RV-idle behavior: the bar stays put. The
        // atTop branch of the bridge handles the tY=0 reset during scroll-to-top.
        colorLnToolbar(collapse)
        val bottomNav = activityBinding?.bottomNav ?: return
        if (bottomNav.isVisible && preferences.hideBottomNavOnScroll().get()) {
            val bH = bottomNav.height.toFloat()
            val targetBottomY = if (!atTop && bottomNav.translationY > bH / 2f) bH else 0f
            if (bottomNav.translationY != targetBottomY) {
                bottomNav.animate()
                    .translationY(targetBottomY)
                    .setDuration(150)
                    .setUpdateListener { setBottomPadding() }
                    .start()
            }
        }
    }

    /** When user activates the LN sub-tab, re-apply the appBar setup `scrollViewWith` would
     *  normally run on a controller lifecycle change. The previous controller might have left
     *  `lockYPos = true` or a different toolbar mode that would block / misrender the LN
     *  collapse. Also enables nested scrolling on the LN ComposeView so the Extensions sheet
     *  drag integrates the same way the manga RV does.
     *
     *  Don't reset [lnTrackedOffset] here, the Compose [LazyColumn]'s [listState] is preserved
     *  by `remember` across tab switches; resetting our tracked offset would desync the appBar
     *  (snapped to 0) from the list (still scrolled), causing the bar to pop fully open on
     *  re-entry even though items are scrolled under it. */
    private fun setupAppBarForLnTab() {
        binding.lnSourcesCompose.isNestedScrollingEnabled = true
        val appBar = activityBinding?.appBar ?: return
        appBar.lockYPos = false
        appBar.setToolbarModeBy(this)
        appBar.useTabsInPreLayout = true
        appBar.hideBigView(false)
        appBar.updateAppBarAfterY(lnScrollingViewAdapter, cancelAnim = true)
        colorLnToolbar(lnTrackedOffset > 0)
    }

    /** Lift-on-scroll tint flip. Mirrors `scrollViewWith.colorToolbar`
     *  (ControllerExtensions.kt:376) which animates between `colorSurface` (at top) and
     *  `colorPrimaryVariant` (scrolled past top) via [setAppBarBG]. */
    private var isLnToolbarTinted = false
    private var lnToolbarColorAnim: ValueAnimator? = null

    private fun colorLnToolbar(tinted: Boolean) {
        if (tinted == isLnToolbarTinted) return
        // Hysteresis: don't flip to tinted until the user has scrolled meaningfully past the
        // top. Without this, microscrolls near offset 0 (where atTop toggles every drag pixel)
        // restart the tint animation on every frame and the bar flickers. The threshold is in
        // dp so it filters the same perceived distance across densities (raw px would suppress
        // a larger fraction of a low-dpi screen than a high-dpi one).
        if (tinted && lnTrackedOffset < 4.dpToPx) return
        isLnToolbarTinted = tinted
        lnToolbarColorAnim?.cancel()
        lnToolbarColorAnim = ValueAnimator.ofFloat(
            if (tinted) 0f else 1f,
            if (tinted) 1f else 0f,
        ).apply {
            addUpdateListener { setAppBarBG(animatedValue as Float, includeTabView = true) }
            duration = 100
            start()
        }
    }

    /**
     * Toggle visibility between the existing manga `source_recycler` and the LN ComposeView
     * based on the active main-tab position. The LN ComposeView is lazy-mounted on first
     * selection so the [yokai.novel.host.LnPluginHost] (WebView + Coil scope) doesn't spin up
     * until needed.
     */
    private fun applySourceTypeTab(position: Int) {
        if (!isBindingInitialized) return
        when (position) {
            0 -> {
                binding.sourceRecycler.isVisible = true
                binding.lnSourcesCompose.isVisible = false
            }
            else -> {
                binding.sourceRecycler.isVisible = false
                binding.lnSourcesCompose.isVisible = true
                setupAppBarForLnTab()
                if (binding.lnSourcesCompose.tag != "compose_mounted") {
                    binding.lnSourcesCompose.setViewCompositionStrategy(
                        androidx.compose.ui.platform.ViewCompositionStrategy
                            .DisposeOnViewTreeLifecycleDestroyed,
                    )
                    binding.lnSourcesCompose.setContent {
                        yokai.presentation.theme.YokaiTheme {
                            val searchQuery by lnSourcesSearchQuery.collectAsState()
                            val paddingPx by lnContentPaddingPx.collectAsState()
                            val density = androidx.compose.ui.platform.LocalDensity.current
                            val contentPadding = androidx.compose.foundation.layout.PaddingValues(
                                top = with(density) { paddingPx.first.toDp() },
                                bottom = with(density) { paddingPx.second.toDp() },
                            )
                            yokai.presentation.novel.sources.LnSourceListContent(
                                searchQuery = searchQuery,
                                contentPadding = contentPadding,
                                onOpenSource = { source ->
                                    if (!preferences.incognitoMode().get()) {
                                        novelPreferences.lastUsedNovelSource().set(source.id)
                                    }
                                    router.pushController(
                                        eu.kanade.tachiyomi.ui.novel.browse.NovelBrowseController(sourceId = source.id)
                                            .withFadeTransaction(),
                                    )
                                },
                                onScrollDelta = ::translateAppBarOnLnScroll,
                                onScrollIdle = ::onLnScrollIdle,
                            )
                        }
                    }
                    binding.lnSourcesCompose.tag = "compose_mounted"
                }
            }
        }
    }

    private fun updateSheetMenu() {
        // Tabs are Manga ext (0) | LN (1) | Migration (2). Manga and LN share the Extensions
        // menu (search bar + extension actions); only Migration uses the migration menu.
        val isMigrationTab = binding.bottomSheet.tabs.selectedTabPosition == 2
        binding.bottomSheet.sheetToolbar.title =
            if (isMigrationTab) {
                binding.bottomSheet.root.currentSourceTitle
                    ?: view?.context?.getString(MR.strings.source_migration)
            } else {
                view?.context?.getString(MR.strings.extensions)
            }
        if (binding.bottomSheet.sheetToolbar.menu.findItem(if (isMigrationTab) R.id.action_migration_guide else R.id.action_search) != null) {
            return
        }
        val oldSearchView = binding.bottomSheet.sheetToolbar.menu.findItem(R.id.action_search)?.actionView as? SearchView
        oldSearchView?.setOnQueryTextListener(null)
        binding.bottomSheet.sheetToolbar.menu.clear()
        binding.bottomSheet.sheetToolbar.inflateMenu(
            if (isMigrationTab) R.menu.migration_main else R.menu.extension_main,
        )

        val id = when (PreferenceValues.MigrationSourceOrder.fromPreference(preferences)) {
            PreferenceValues.MigrationSourceOrder.Alphabetically -> R.id.action_sort_alpha
            PreferenceValues.MigrationSourceOrder.MostEntries -> R.id.action_sort_largest
            PreferenceValues.MigrationSourceOrder.Obsolete -> R.id.action_sort_obsolete
        }
        binding.bottomSheet.sheetToolbar.menu.findItem(id)?.isChecked = true

        // Initialize search option.
        binding.bottomSheet.sheetToolbar.menu.findItem(R.id.action_search)?.let { searchItem ->
            val searchView = searchItem.actionView as SearchView

            // Change hint to show global search.
            searchView.queryHint = view?.context?.getString(MR.strings.search_extensions)
            if (extQuery.value.isNotEmpty()) {
                searchView.setOnQueryTextListener(null)
                searchItem.expandActionView()
                searchView.setQuery(extQuery.value, true)
                searchView.clearFocus()
            } else {
                searchItem.collapseActionView()
            }
            // Create query listener which opens the global search view.
            setOnQueryTextChangeListener(searchView) {
                _extQuery.value = it ?: ""
                binding.bottomSheet.root.drawExtensions()
                true
            }
        }
    }

    private fun setSheetToolbar() {
        binding.bottomSheet.sheetToolbar.setOnMenuItemClickListener { item ->
            val sorting = when (item.itemId) {
                R.id.action_sort_alpha -> PreferenceValues.MigrationSourceOrder.Alphabetically
                R.id.action_sort_largest -> PreferenceValues.MigrationSourceOrder.MostEntries
                R.id.action_sort_obsolete -> PreferenceValues.MigrationSourceOrder.Obsolete
                else -> null
            }
            if (sorting != null) {
                preferences.migrationSourceOrder().set(sorting.value)
                binding.bottomSheet.root.presenter.refreshMigrations()
                item.isChecked = true
                return@setOnMenuItemClickListener true
            }
            when (item.itemId) {
                // Initialize option to open catalogue settings.
                R.id.action_filter -> {
                    router.pushController(ExtensionFilterController().withFadeTransaction())
                }
                R.id.action_migration_guide -> {
                    activity?.openInBrowser(HELP_URL)
                }
                R.id.action_sources_settings -> {
                    router.pushController(SettingsBrowseController().withFadeTransaction())
                }
                R.id.action_extension_repos_settings -> {
                    router.pushController(ExtensionRepoController().withFadeTransaction())
                }
            }
            return@setOnMenuItemClickListener true
        }
        binding.bottomSheet.sheetToolbar.setNavigationOnClickListener {
            binding.bottomSheet.root.sheetBehavior?.collapse()
        }
        updateSheetMenu()
    }

    fun updateTitleAndMenu() {
        if (isControllerVisible) {
            val activity = (activity as? MainActivity) ?: return
            activityBinding?.appBar?.isInvisible = showingExtensions
            (activity as? MainActivity)?.setStatusBarColorTransparent(showingExtensions)
            updateSheetMenu()
        }
    }

    fun setBottomSheetTabs(progress: Float) {
        val bottomSheet = binding.bottomSheet.root
        val halfStepProgress = (max(0.5f, progress) - 0.5f) * 2
        binding.bottomSheet.tabs.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = (
                (
                    activityBinding?.appBar?.paddingTop
                        ?.minus(9f.dpToPx)
                        ?.plus(toolbarHeight ?: 0) ?: 0f
                    ) * halfStepProgress
                ).toInt()
        }
        binding.bottomSheet.pill.alpha = (1 - progress) * 0.25f
        binding.bottomSheet.sheetToolbar.alpha = progress
        if (isControllerVisible) {
            activityBinding?.appBar?.alpha = (1 - progress * 3) + 0.5f
        }

        binding.bottomSheet.root.updateGradiantBGRadius(
            ogRadius,
            deviceRadius,
            progress,
            binding.bottomSheet.sheetLayout,
        )

        val selectedColor = ColorUtils.setAlphaComponent(
            bottomSheet.context.getResourceColor(R.attr.tabBarIconColor),
            (progress * 255).toInt(),
        )
        val unselectedColor = ColorUtils.setAlphaComponent(
            bottomSheet.context.getResourceColor(R.attr.actionBarTintColor),
            153,
        )
        binding.bottomSheet.pager.alpha = progress * 10
        binding.bottomSheet.tabs.setSelectedTabIndicatorColor(selectedColor)
        binding.bottomSheet.tabs.setTabTextColors(
            ColorUtils.blendARGB(
                bottomSheet.context.getResourceColor(R.attr.actionBarTintColor),
                unselectedColor,
                progress,
            ),
            ColorUtils.blendARGB(
                bottomSheet.context.getResourceColor(R.attr.actionBarTintColor),
                selectedColor,
                progress,
            ),
        )

        /*binding.bottomSheet.sheetLayout.backgroundTintList = ColorStateList.valueOf(
            ColorUtils.blendARGB(
                bottomSheet.context.getResourceColor(R.attr.colorPrimaryVariant),
                bottomSheet.context.getResourceColor(R.attr.colorSurface),
                progress
            )
        )*/
    }

    private fun setBottomPadding() {
        val bottomBar = activityBinding?.bottomNav
        val pad = bottomBar?.translationY?.minus(bottomBar.height) ?: 0f
        val padding = max(
            (-pad).toInt(),
            view?.rootWindowInsetsCompat?.getBottomGestureInsets() ?: 0,
        )
        binding.bottomSheet.root.sheetBehavior?.peekHeight = 56.spToPx + padding
        binding.bottomSheet.root.extensionFrameLayout?.binding?.fastScroller?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = -pad.toInt()
        }
        binding.bottomSheet.root.migrationFrameLayout?.binding?.fastScroller?.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            bottomMargin = -pad.toInt()
        }
        binding.sourceRecycler.updatePaddingRelative(
            bottom = (
                activityBinding?.bottomNav?.height
                    ?: view?.rootWindowInsetsCompat?.getBottomGestureInsets() ?: 0
                ) + 58.spToPx,
        )
    }

    override fun showSheet() {
        if (!isBindingInitialized) return
        binding.bottomSheet.root.sheetBehavior?.expand()
    }

    override fun hideSheet() {
        if (!isBindingInitialized) return
        binding.bottomSheet.root.sheetBehavior?.collapse()
    }

    override fun toggleSheet() {
        if (!binding.bottomSheet.root.sheetBehavior.isCollapsed()) {
            binding.bottomSheet.root.sheetBehavior?.collapse()
        } else {
            binding.bottomSheet.root.sheetBehavior?.expand()
        }
    }

    override fun canStillGoBack(): Boolean = showingExtensions

    override fun handleOnBackStarted(backEvent: BackEventCompat) {
        if (showingExtensions && !binding.bottomSheet.root.canStillGoBack()) {
            binding.bottomSheet.root.sheetBehavior?.startBackProgress(backEvent)
        }
    }

    override fun handleOnBackProgressed(backEvent: BackEventCompat) {
        if (showingExtensions && !binding.bottomSheet.root.canStillGoBack()) {
            binding.bottomSheet.root.sheetBehavior?.updateBackProgress(backEvent)
        } else {
            super.handleOnBackProgressed(backEvent)
        }
    }

    override fun handleOnBackCancelled() {
        if (showingExtensions && !binding.bottomSheet.root.canStillGoBack()) {
            binding.bottomSheet.root.sheetBehavior?.cancelBackProgress()
        } else {
            super.handleOnBackCancelled()
        }
    }

    override fun handleBack(): Boolean {
        if (showingExtensions) {
            if (binding.bottomSheet.root.canGoBack()) {
                lastScale = binding.bottomSheet.root.scaleX
                binding.bottomSheet.root.sheetBehavior?.collapse()
            }
            return true
        }
        return false
    }

    override fun onDestroyView(view: View) {
        adapter = null
        binding.bottomSheet.root.onDestroy()
        super.onDestroyView(view)
    }

    override fun onDestroy() {
        super.onDestroy()
        presenter.onDestroy()
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (!type.isPush) {
            binding.bottomSheet.root.updateExtTitle()
            binding.bottomSheet.root.presenter.refreshExtensions()
            presenter.updateSources()
            if (type.isEnter && isControllerVisible) {
                activityBinding?.appBar?.doOnNextLayout {
                    activityBinding?.appBar?.y = 0f
                    activityBinding?.appBar?.updateAppBarAfterY(binding.sourceRecycler)
                }
                updateSheetMenu()
            }
        }
        if (!type.isEnter) {
            binding.bottomSheet.root.canExpand = false
            activityBinding?.appBar?.alpha = 1f
            activityBinding?.appBar?.isInvisible = router.isCompose
            binding.bottomSheet.sheetToolbar.menu.findItem(R.id.action_search)?.let { searchItem ->
                val searchView = searchItem.actionView as SearchView
                searchView.clearFocus()
            }
            // Hide the source-type tab row when leaving Browse (mirrors RecentsController). The
            // tabs live in the activity's mainTabs; not hiding them would leak into whichever
            // controller is pushed on top. Skip when the incoming controller is also
            // TabbedInterface (it will install its own tabs on entry); otherwise our hide
            // animation cancels theirs and clears mainTabs after they set it up.
            val nextController = router.backstack.lastOrNull()?.controller
            if (nextController !is TabbedInterface) {
                (activity as? MainActivity)?.showTabBar(
                    show = false,
                    animate = nextController !is SmallToolbarInterface,
                )
            }
        } else {
            binding.bottomSheet.root.presenter.refreshMigrations()
            updateTitleAndMenu()
            // PUSH_ENTER is handled in onViewCreated (which fires on a fresh controller
            // before this callback). Re-install tabs only on POP_ENTER from a sub-controller,
            // where showTabBar(false) on PUSH_EXIT already cleared mainTabs.
            if (!type.isPush && isControllerVisible) {
                setupSourceTypeTabs()
            }
        }
        setBottomPadding()
    }

    override fun onChangeEnded(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeEnded(handler, type)
        if (type.isEnter) {
            binding.bottomSheet.root.canExpand = true
            setBottomPadding()
            updateTitleAndMenu()
        }
    }

    override fun onActivityResumed(activity: Activity) {
        super.onActivityResumed(activity)
        if (!isBindingInitialized) return
        binding.bottomSheet.root.presenter.refreshExtensions()
        binding.bottomSheet.root.presenter.refreshMigrations()
        setBottomPadding()
        if (showingExtensions) {
            updateSheetMenu()
        }
    }

    override fun onItemClick(view: View, position: Int): Boolean {
        val item = adapter?.getItem(position) as? SourceItem ?: return false
        val source = item.source
        // Open the catalogue view.
        openCatalogue(source, BrowseSourceController(source))
        return false
    }

    fun hideCatalogue(position: Int) {
        val source = (adapter?.getItem(position) as? SourceItem)?.source ?: return
        val current = preferences.hiddenSources().get()
        preferences.hiddenSources().set(current + source.id.toString())

        presenter.updateSources()

        snackbar = view?.snack(MR.strings.source_hidden, Snackbar.LENGTH_INDEFINITE) {
            anchorView = binding.bottomSheet.root
            setAction(MR.strings.undo) {
                val newCurrent = preferences.hiddenSources().get()
                preferences.hiddenSources().set(newCurrent - source.id.toString())
                presenter.updateSources()
            }
        }
        (activity as? MainActivity)?.setUndoSnackBar(snackbar)
    }

    private fun pinCatalogue(source: Source, isPinned: Boolean) {
        val current = preferences.pinnedCatalogues().get()
        if (isPinned) {
            preferences.pinnedCatalogues().set(current - source.id.toString())
        } else {
            preferences.pinnedCatalogues().set(current + source.id.toString())
        }

        presenter.updateSources()
    }

    /**
     * Called when browse is clicked in [SourceAdapter]
     */
    override fun onPinClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return
        val isPinned = item.isPinned ?: item.header?.code?.equals(SourcePresenter.PINNED_KEY)
            ?: false
        pinCatalogue(item.source, isPinned)
    }

    /**
     * Called when latest is clicked in [SourceAdapter]
     */
    override fun onLatestClick(position: Int) {
        val item = adapter?.getItem(position) as? SourceItem ?: return
        openCatalogue(item.source, BrowseSourceController(item.source, useLatest = true))
    }

    /**
     * Opens a catalogue with the given controller.
     */
    private fun openCatalogue(source: CatalogueSource, controller: BrowseSourceController) {
        if (!preferences.incognitoMode().get()) {
            preferences.lastUsedCatalogueSource().set(source.id)
            if (source !is LocalSource) {
                val list = preferences.lastUsedSources().get().toMutableSet()
                list.removeAll { it.startsWith("${source.id}:") }
                list.add("${source.id}:${Date().time}")
                val sortedList = list.filter { it.split(":").size == 2 }
                    .sortedByDescending { it.split(":").last().toLong() }
                preferences.lastUsedSources()
                    .set(sortedList.take(2).toSet())
            }
        }
        router.pushController(controller.withFadeTransaction())
    }

    override fun expandSearch() {
        if (showingExtensions) {
            binding.bottomSheet.root.sheetBehavior?.collapse()
        } else {
            activityBinding?.searchToolbar?.menu?.findItem(R.id.action_search)?.expandActionView()
        }
    }

    /**
     * Adds items to the options menu.
     *
     * @param menu menu containing options.
     * @param inflater used to load the menu xml.
     */
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        // Inflate menu
        inflater.inflate(R.menu.catalogue_main, menu)

        // Initialize search option.
        val searchView = activityBinding?.searchToolbar?.searchView

        // Change hint to show global search.
        activityBinding?.searchToolbar?.searchQueryHint = view?.context?.getString(MR.strings.global_search)

        // Direct OnQueryTextListener (not the helper) so text-change can drive the LN sources
        // filter live while submit keeps firing the manga global-search controller. The helper
        // forces a single onlyOnSubmit mode; we need both.
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextChange(newText: String?): Boolean {
                if (router.backstack.lastOrNull()?.controller != this@BrowseController) return false
                _lnSourcesSearchQuery.value = newText.orEmpty()
                return true
            }

            override fun onQueryTextSubmit(query: String?): Boolean {
                if (!isControllerVisible) return true
                (activity?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                    ?.hideSoftInputFromWindow(searchView.windowToken, 0)
                if (!query.isNullOrBlank()) performGlobalSearch(query)
                return true
            }
        })
    }

    private fun performGlobalSearch(query: String) {
        router.pushController(GlobalSearchController(query).withFadeTransaction())
    }

    /**
     * Called when an option menu item has been selected by the user.
     *
     * @param item The selected item.
     * @return True if this event has been consumed, false if it has not.
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            // Initialize option to open catalogue settings.
            R.id.action_filter -> {
                router.pushController(SettingsSourcesController().withFadeTransaction())
            }
            R.id.action_migration_guide -> {
                activity?.openInBrowser(HELP_URL)
            }
            R.id.action_sources_settings -> {
                router.pushController(SettingsBrowseController().withFadeTransaction())
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    /**
     * Called to update adapter containing sources.
     */
    fun setSources(sources: List<IFlexible<*>>, lastUsed: SourceItem?) {
        adapter?.updateDataSet(sources, false)
        setLastUsedSource(lastUsed)
        if (isControllerVisible) {
            activityBinding?.appBar?.lockYPos = false
        }
    }

    /**
     * Called to set the last used catalogue at the top of the view.
     */
    fun setLastUsedSource(item: SourceItem?) {
        adapter?.removeAllScrollableHeaders()
        if (item != null) {
            adapter?.addScrollableHeader(item)
            adapter?.addScrollableHeader(LangItem(SourcePresenter.LAST_USED_KEY))
        }
    }

    @Parcelize
    data class SmartSearchConfig(val origTitle: String, val origMangaId: Long) : Parcelable

    companion object {
        const val HELP_URL = "https://tachiyomi.org/docs/guides/source-migration"
    }
}
