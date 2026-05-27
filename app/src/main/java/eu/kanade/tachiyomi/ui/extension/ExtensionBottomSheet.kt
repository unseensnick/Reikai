package eu.kanade.tachiyomi.ui.extension

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager.widget.ViewPager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.tabs.TabLayout
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.databinding.ExtensionsBottomSheetBinding
import eu.kanade.tachiyomi.databinding.RecyclerWithScrollerBinding
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.extension.model.InstallStep
import eu.kanade.tachiyomi.extension.model.InstalledExtensionsOrder
import eu.kanade.tachiyomi.ui.extension.details.ExtensionDetailsController
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.migration.BaseMigrationInterface
import eu.kanade.tachiyomi.ui.migration.MangaAdapter
import eu.kanade.tachiyomi.ui.migration.MangaItem
import eu.kanade.tachiyomi.ui.migration.SourceAdapter
import eu.kanade.tachiyomi.ui.migration.SourceItem
import eu.kanade.tachiyomi.ui.migration.manga.design.PreMigrationController
import eu.kanade.tachiyomi.ui.source.BrowseController
import eu.kanade.tachiyomi.util.system.isPackageInstalled
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import eu.kanade.tachiyomi.util.system.rootWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.collapse
import eu.kanade.tachiyomi.util.view.doOnApplyWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.expand
import eu.kanade.tachiyomi.util.view.isExpanded
import eu.kanade.tachiyomi.util.view.popupMenu
import eu.kanade.tachiyomi.util.view.setMessage
import eu.kanade.tachiyomi.util.view.setNegativeButton
import eu.kanade.tachiyomi.util.view.setPositiveButton
import eu.kanade.tachiyomi.util.view.setText
import eu.kanade.tachiyomi.util.view.setTitle
import eu.kanade.tachiyomi.util.view.smoothScrollToTop
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.domain.base.BasePreferences
import yokai.domain.base.BasePreferences.ExtensionInstaller
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

class ExtensionBottomSheet @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs),
    ExtensionAdapter.OnButtonClickListener,
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    SourceAdapter.OnAllClickListener,
    BaseMigrationInterface {

    private val basePreferences: BasePreferences by injectLazy()

    var sheetBehavior: BottomSheetBehavior<*>? = null

    var shouldCallApi = false

    /**
     * Adapter containing the list of extensions
     */
    private var extAdapter: ExtensionAdapter? = null
    private var migAdapter: FlexibleAdapter<IFlexible<*>>? = null

    /** Page-indexed: 0 = manga ext, 1 = LN (Compose, no FlexibleAdapter), 2 = migration. */
    val adapters: List<FlexibleAdapter<IFlexible<*>>?>
        get() = listOf(extAdapter, null, migAdapter)

    private var lnComposeView: ComposeView? = null

    /** Unfiltered caches so [pushMigrationData] can re-apply the shared toolbar search on
     *  every [controller]'s extQuery change without losing the source-of-truth list. */
    private var migrationSources: List<SourceItem> = emptyList()
    private var migrationManga: List<MangaItem>? = null

    val presenter = ExtensionBottomPresenter()
    var currentSourceTitle: String? = null

    private var extensions: List<ExtensionItem> = emptyList()
    var canExpand = false
    private lateinit var binding: ExtensionsBottomSheetBinding

    lateinit var controller: BrowseController
    var boundViews = arrayListOf<RecyclerWithScrollerView>()

    val extensionFrameLayout: RecyclerWithScrollerView?
        get() = binding.pager.findViewWithTag("TabbedRecycler0") as? RecyclerWithScrollerView
    val migrationFrameLayout: RecyclerWithScrollerView?
        get() = binding.pager.findViewWithTag("TabbedRecycler2") as? RecyclerWithScrollerView

    var isExpanding = false

    override fun onFinishInflate() {
        super.onFinishInflate()
        binding = ExtensionsBottomSheetBinding.bind(this)
    }

    fun onCreate(controller: BrowseController) {
        // Initialize adapter, scroll listener and recycler views
        presenter.attachView(this)
        extAdapter = ExtensionAdapter(this)
        extAdapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        if (migAdapter == null) {
            migAdapter = SourceAdapter(this)
        }
        migAdapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        sheetBehavior = BottomSheetBehavior.from(this)
        // Create recycler and set adapter.

        binding.pager.adapter = TabbedSheetAdapter()
        binding.tabs.setupWithViewPager(binding.pager)
        this.controller = controller
        binding.pager.doOnApplyWindowInsetsCompat { _, insets, _ ->
            val bottomBar = controller.activityBinding?.bottomNav
            val bottomH = bottomBar?.height ?: insets.getInsets(systemBars()).bottom
            extensionFrameLayout?.binding?.recycler?.updatePaddingRelative(bottom = bottomH)
            migrationFrameLayout?.binding?.recycler?.updatePaddingRelative(bottom = bottomH)
            lnComposeView?.updatePaddingRelative(bottom = bottomH)
        }
        binding.tabs.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    isExpanding = !sheetBehavior.isExpanded()
                    if (canExpand) {
                        this@ExtensionBottomSheet.sheetBehavior?.expand()
                    }
                    this@ExtensionBottomSheet.controller.updateTitleAndMenu()
                    val rv = activeNestedScrollChild(tab?.position)
                    rv?.isNestedScrollingEnabled = true
                    rv?.requestLayout()
                    sheetBehavior?.isDraggable = true
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {
                    activeNestedScrollChild(tab?.position)?.isNestedScrollingEnabled = false
                    if (tab?.position == 2) {
                        presenter.deselectSource()
                    }
                }

                override fun onTabReselected(tab: TabLayout.Tab?) {
                    isExpanding = !sheetBehavior.isExpanded()
                    this@ExtensionBottomSheet.sheetBehavior?.expand()
                    activeNestedScrollChild(tab?.position)?.isNestedScrollingEnabled = true
                    sheetBehavior?.isDraggable = true
                    if (!isExpanding) {
                        activeRecyclerView(tab?.position)?.smoothScrollToTop()
                    }
                }
            },
        )
        // Lazy-mount the LN Compose surface on first selection of page 1 so the LnPluginHost
        // WebView doesn't spin up just because the sheet opened. Page 1 is always retained in
        // memory once instantiated (3 pages, ViewPager offscreenPageLimit = 1), so the field
        // and mount-once-via-tag combo holds across subsequent page swipes.
        binding.pager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
            override fun onPageSelected(position: Int) {
                if (position == 1) {
                    val cv = lnComposeView ?: return
                    if (cv.tag != COMPOSE_MOUNTED_TAG) {
                        mountLnContent(cv)
                        cv.tag = COMPOSE_MOUNTED_TAG
                    }
                }
            }
        })
        presenter.onCreate()
        updateExtTitle()

        binding.sheetLayout.setOnClickListener {
            if (!sheetBehavior.isExpanded()) {
                sheetBehavior?.expand()
                fetchOnlineExtensionsIfNeeded()
            } else {
                sheetBehavior?.collapse()
            }
        }
        presenter.getExtensionUpdateCount()
    }

    fun isOnView(view: View): Boolean {
        val current = binding.pager.currentItem
        return when (view.tag) {
            "TabbedRecycler$current", "TabbedCompose$current" -> true
            else -> false
        }
    }

    fun updatedNestedRecyclers() {
        val current = binding.pager.currentItem
        extensionFrameLayout?.binding?.recycler?.isNestedScrollingEnabled = current == 0
        lnComposeView?.isNestedScrollingEnabled = current == 1
        migrationFrameLayout?.binding?.recycler?.isNestedScrollingEnabled = current == 2
    }

    /** Returns the view whose nested-scroll state should be toggled with the active tab. RVs
     *  default to nested-scroll-enabled; the LN ComposeView defaults to disabled so we
     *  explicitly drive both. */
    private fun activeNestedScrollChild(position: Int?): View? = when (position) {
        0 -> extensionFrameLayout?.binding?.recycler
        1 -> lnComposeView
        2 -> migrationFrameLayout?.binding?.recycler
        else -> null
    }

    /** Returns the RV for the active tab, or null on the LN tab. Used for RV-specific calls
     *  (smoothScrollToTop) that have no LazyColumn equivalent surfaced to the View layer. */
    private fun activeRecyclerView(position: Int?): RecyclerView? = when (position) {
        0 -> extensionFrameLayout?.binding?.recycler
        2 -> migrationFrameLayout?.binding?.recycler
        else -> null
    }

    private fun mountLnContent(composeView: ComposeView) {
        // ComposeView is a NestedScrollingChild but defaults to disabled. Without this the
        // LazyColumn inside doesn't dispatch its scroll deltas to the parent CoordinatorLayout,
        // so dragging the bottom sheet from a scrolled-mid-LN-list state doesn't grab.
        composeView.isNestedScrollingEnabled = true
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed,
        )
        composeView.setContent {
            yokai.presentation.theme.YokaiTheme {
                val query by controller.extQuery.collectAsState()
                yokai.presentation.extension.browse.LnPluginBrowseContent(searchQuery = query)
            }
        }
    }

    fun fetchOnlineExtensionsIfNeeded() {
        if (shouldCallApi) {
            presenter.findAvailableExtensions()
            shouldCallApi = false
        }
    }

    fun updateExtTitle() {
        val extCount = presenter.getExtensionUpdateCount()
        if (extCount > 0) {
            binding.tabs.getTabAt(0)?.orCreateBadge
        } else {
            binding.tabs.getTabAt(0)?.removeBadge()
        }
    }

    override fun onButtonClick(position: Int) {
        val extension = (extAdapter?.getItem(position) as? ExtensionItem)?.extension ?: return
        when (extension) {
            is Extension.Installed -> {
                if (!extension.hasUpdate) {
                    openDetails(extension)
                } else {
                    presenter.updateExtension(extension)
                }
            }
            is Extension.Available -> {
                presenter.installExtension(extension)
            }
            is Extension.Untrusted -> {
                openTrustDialog(extension)
            }
        }
    }

    override fun onCancelClick(position: Int) {
        val extension = (extAdapter?.getItem(position) as? ExtensionItem) ?: return
        presenter.cancelExtensionInstall(extension)
    }

    override fun onUpdateAllClicked(position: Int) {
        (controller.activity as? MainActivity)?.showNotificationPermissionPrompt()
        if (basePreferences.extensionInstaller().get() != ExtensionInstaller.SHIZUKU &&
            !presenter.preferences.hasPromptedBeforeUpdateAll().get()
        ) {
            controller.activity!!.materialAlertDialog()
                .setTitle(MR.strings.update_all)
                .setMessage(MR.strings.some_extensions_may_prompt)
                .setPositiveButton(AR.string.ok) { _, _ ->
                    presenter.preferences.hasPromptedBeforeUpdateAll().set(true)
                    updateAllExtensions(position)
                }
                .show()
        } else {
            updateAllExtensions(position)
        }
    }

    override fun onExtSortClicked(view: TextView, position: Int) {
        view.popupMenu(
            InstalledExtensionsOrder.entries.map { it.value to it.nameRes },
            presenter.preferences.installedExtensionsOrder().get(),
        ) {
            presenter.preferences.installedExtensionsOrder().set(itemId)
            extAdapter?.installedSortOrder = itemId
            view.setText(InstalledExtensionsOrder.fromValue(itemId).nameRes)
            presenter.refreshExtensions()
        }
    }

    private fun updateAllExtensions(position: Int) {
        val header = (extAdapter?.getSectionHeader(position)) as? ExtensionGroupItem ?: return
        val items = extAdapter?.getSectionItemPositions(header)
        val extensions = items?.mapNotNull {
            val extItem = (extAdapter?.getItem(it) as? ExtensionItem) ?: return
            val extension = (extAdapter?.getItem(it) as? ExtensionItem)?.extension ?: return
            if ((extItem.installStep == null || extItem.installStep == InstallStep.Error) &&
                extension is Extension.Installed && extension.hasUpdate
            ) {
                extension
            } else {
                null
            }
        }.orEmpty()
        presenter.updateExtensions(extensions)
    }

    override fun onItemClick(view: View?, position: Int): Boolean {
        when (binding.tabs.selectedTabPosition) {
            0 -> {
                val extension =
                    (extAdapter?.getItem(position) as? ExtensionItem)?.extension ?: return false
                if (extension is Extension.Installed) {
                    openDetails(extension)
                } else if (extension is Extension.Untrusted) {
                    openTrustDialog(extension)
                }
            }
            2 -> {
                val item = migAdapter?.getItem(position) ?: return false

                if (item is MangaItem) {
                    PreMigrationController.navigateToMigration(
                        Injekt.get<PreferencesHelper>().skipPreMigration().get(),
                        controller.router,
                        listOf(item.manga.id!!),
                    )
                } else if (item is SourceItem) {
                    presenter.setSelectedSource(item.source)
                }
            }
        }
        return false
    }

    override fun onItemLongClick(position: Int) {
        if (binding.tabs.selectedTabPosition == 0) {
            val extension = (extAdapter?.getItem(position) as? ExtensionItem)?.extension ?: return
            if (extension is Extension.Installed || extension is Extension.Untrusted) {
                uninstallExtension(extension.name, extension.pkgName)
            }
        }
    }

    override fun onAllClick(position: Int) {
        val item = migAdapter?.getItem(position) as? SourceItem ?: return

        val sourceMangas =
            presenter.mangaItems[item.source.id]?.mapNotNull { it.manga.id }?.toList()
                ?: emptyList()
        PreMigrationController.navigateToMigration(
            Injekt.get<PreferencesHelper>().skipPreMigration().get(),
            controller.router,
            sourceMangas,
        )
    }

    private fun openDetails(extension: Extension.Installed) {
        val controller = ExtensionDetailsController(extension.pkgName)
        this.controller.router.pushController(controller.withFadeTransaction())
    }

    private fun openTrustDialog(extension: Extension.Untrusted) {
        val activity = controller.activity ?: return
        activity.materialAlertDialog()
            .setTitle(MR.strings.untrusted_extension)
            .setMessage(MR.strings.untrusted_extension_message)
            .setPositiveButton(MR.strings.trust) { _, _ ->
                trustExtension(extension.pkgName, extension.versionCode, extension.signatureHash)
            }
            .setNegativeButton(MR.strings.uninstall) { _, _ ->
                uninstallExtension(extension.pkgName)
            }.show()
    }

    fun setExtensions(extensions: List<ExtensionItem>, updateController: Boolean = true) {
        this.extensions = extensions
        if (updateController) {
            controller.presenter.updateSources()
        }
        drawExtensions()
    }

    override fun setMigrationSources(sources: List<SourceItem>) {
        currentSourceTitle = null
        val changingAdapters = migAdapter !is SourceAdapter
        if (migAdapter !is SourceAdapter) {
            migAdapter = SourceAdapter(this)
            migrationFrameLayout?.onBind(migAdapter!!)
            migAdapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        migrationSources = sources
        migrationManga = null
        pushMigrationData(changingAdapters)
        controller.updateTitleAndMenu()
    }

    override fun setMigrationManga(title: String, manga: List<MangaItem>?) {
        currentSourceTitle = title
        val changingAdapters = migAdapter !is MangaAdapter
        if (migAdapter !is MangaAdapter) {
            migAdapter = MangaAdapter(this, presenter.uiPreferences.outlineOnCovers().get())
            migrationFrameLayout?.onBind(migAdapter!!)
            migAdapter?.stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
        }
        migrationManga = manga
        pushMigrationData(changingAdapters)
        controller.updateTitleAndMenu()
    }

    /** Re-apply the shared toolbar search to whichever migration list is current (source-list
     *  or per-source manga-list). Called from setters above and from [drawExtensions] so a
     *  text-change event refreshes both the manga ext list and the migration list in one pass. */
    private fun pushMigrationData(changingAdapters: Boolean = false) {
        val query = controller.extQuery.value
        when (val adapter = migAdapter) {
            is SourceAdapter -> {
                val filtered = if (query.isBlank()) migrationSources else migrationSources.filter {
                    it.source.name.contains(query, ignoreCase = true)
                }
                adapter.updateDataSet(filtered, changingAdapters)
            }
            is MangaAdapter -> {
                val data = migrationManga
                val filtered = when {
                    data == null -> null
                    query.isBlank() -> data
                    else -> data.filter { it.manga.title.contains(query, ignoreCase = true) }
                }
                adapter.updateDataSet(filtered, changingAdapters)
            }
            else -> Unit
        }
    }

    fun drawExtensions() {
        val query = controller.extQuery.value
        if (query.isNotBlank()) {
            extAdapter?.updateDataSet(
                extensions.filter {
                    it.extension.name.contains(query, ignoreCase = true)
                },
            )
        } else {
            extAdapter?.updateDataSet(extensions)
        }
        pushMigrationData()
        updateExtTitle()
        updateExtUpdateAllButton()
    }

    fun canStillGoBack(): Boolean {
        return (binding.tabs.selectedTabPosition == 2 && migAdapter is MangaAdapter) ||
            (binding.tabs.selectedTabPosition == 0 && binding.sheetToolbar.hasExpandedActionView())
    }

    fun canGoBack(): Boolean {
        return if (binding.tabs.selectedTabPosition == 2 && migAdapter is MangaAdapter) {
            presenter.deselectSource()
            false
        } else if (binding.sheetToolbar.hasExpandedActionView()) {
            binding.sheetToolbar.collapseActionView()
            false
        } else {
            true
        }
    }

    fun downloadUpdate(item: ExtensionItem) {
        extAdapter?.updateItem(item, item.installStep)
        updateExtUpdateAllButton()
    }

    private fun updateExtUpdateAllButton() {
        val updateHeader =
            extAdapter?.headerItems?.find { it is ExtensionGroupItem && it.canUpdate != null } as? ExtensionGroupItem
                ?: return
        val items = extAdapter?.getSectionItemPositions(updateHeader) ?: return
        updateHeader.canUpdate = items.any {
            val extItem = (extAdapter?.getItem(it) as? ExtensionItem) ?: return
            extItem.installStep == null || extItem.installStep == InstallStep.Error
        }
        extAdapter?.updateItem(updateHeader)
    }

    private fun trustExtension(pkgName: String, versionCode: Long, signatureHash: String) {
        presenter.trustExtension(pkgName, versionCode, signatureHash)
    }

    private fun uninstallExtension(pkgName: String) {
        presenter.uninstallExtension(pkgName)
    }

    private fun uninstallExtension(extName: String, pkgName: String) {
        if (context.isPackageInstalled(pkgName)) {
            presenter.uninstallExtension(pkgName)
        } else {
            controller.activity!!.materialAlertDialog()
                .setTitle(extName)
                .setPositiveButton(MR.strings.remove) { _, _ ->
                    presenter.uninstallExtension(pkgName)
                }
                .setNegativeButton(AR.string.cancel, null)
                .show()
        }
    }

    fun setCanInstallPrivately(installPrivately: Boolean) {
        extAdapter?.installPrivately = installPrivately
    }

    fun onDestroy() {
        presenter.onDestroy()
    }

    private inner class TabbedSheetAdapter : RecyclerViewPagerAdapter() {

        init {
            // Disable view pool recycling: the three pages have structurally different views
            // (RV / ComposeView / RV) and the parent's pool would otherwise hand a ComposeView
            // to an RV position (or vice versa) when a page is destroyed and recreated.
            recycle = false
        }

        override fun getCount(): Int = 3

        override fun getPageTitle(position: Int): CharSequence = context.getString(
            when (position) {
                0 -> MR.strings.manga
                1 -> MR.strings.light_novels
                else -> MR.strings.migration
            },
        )

        override fun createView(container: ViewGroup, position: Int): View {
            val view = when (position) {
                1 -> createLnComposeView(container)
                else -> createView(container)
            }
            bindView(view, position)
            return view
        }

        /** Bare [RecyclerWithScrollerView] page used by both the Manga ext tab (0) and the
         *  Migration tab (2). Default implementation required by the parent class; never
         *  called for the LN page since [createView] intercepts position 1. */
        override fun createView(container: ViewGroup): View {
            val binding = RecyclerWithScrollerBinding.inflate(
                LayoutInflater.from(container.context),
                container,
                false,
            )
            val view: RecyclerWithScrollerView = binding.root
            val height = this@ExtensionBottomSheet.controller.activityBinding?.bottomNav?.height
                ?: view.rootWindowInsetsCompat?.getInsets(systemBars())?.bottom ?: 0
            view.setUp(this@ExtensionBottomSheet, binding, height)
            return view
        }

        /** LN tab page: a bare [ComposeView]. Composition is deferred to first selection of
         *  this page (see [mountLnContent] hook in onCreate) so the LnPluginHost WebView only
         *  spins up when the user actually navigates to LN. */
        private fun createLnComposeView(container: ViewGroup): View {
            val cv = ComposeView(container.context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
            }
            this@ExtensionBottomSheet.lnComposeView = cv
            return cv
        }

        override fun bindView(view: View, position: Int) {
            if (position == 1) {
                // LN page has no FlexibleAdapter; tag it so isOnView() can identify it and
                // skip adapter binding entirely.
                view.tag = "TabbedCompose1"
                return
            }
            val rv = view as? RecyclerWithScrollerView ?: return
            rv.onBind(adapters[position]!!)
            rv.tag = "TabbedRecycler$position"
            boundViews.add(rv)
        }

        override fun recycleView(view: View, position: Int) {
            if (position == 1) {
                if (view === this@ExtensionBottomSheet.lnComposeView) {
                    this@ExtensionBottomSheet.lnComposeView = null
                }
                return
            }
            val rv = view as? RecyclerWithScrollerView ?: return
            boundViews.remove(rv)
        }

        override fun getItemPosition(obj: Any): Int {
            if (obj is ComposeView) return 1
            val rv = obj as? RecyclerWithScrollerView ?: return POSITION_NONE
            val index = adapters.indexOfFirst { it == rv.binding?.recycler?.adapter }
            return if (index == -1) POSITION_NONE else index
        }
    }

    companion object {
        private const val COMPOSE_MOUNTED_TAG = "compose_mounted"
    }
}
