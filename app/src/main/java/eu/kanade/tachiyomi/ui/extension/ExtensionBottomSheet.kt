package eu.kanade.tachiyomi.ui.extension

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import androidx.core.view.updatePaddingRelative
import androidx.recyclerview.widget.RecyclerView
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

    val adapters
        get() = listOf(extAdapter, migAdapter)

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
        get() = binding.pager.findViewWithTag("TabbedRecycler1") as? RecyclerWithScrollerView

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
        }
        binding.tabs.addOnTabSelectedListener(
            object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab?) {
                    isExpanding = !sheetBehavior.isExpanded()
                    if (canExpand) {
                        this@ExtensionBottomSheet.sheetBehavior?.expand()
                    }
                    this@ExtensionBottomSheet.controller.updateTitleAndMenu()
                    when (tab?.position) {
                        0 -> extensionFrameLayout
                        else -> migrationFrameLayout
                    }?.binding?.recycler?.isNestedScrollingEnabled = true
                    when (tab?.position) {
                        0 -> extensionFrameLayout
                        else -> migrationFrameLayout
                    }?.binding?.recycler?.requestLayout()
                    sheetBehavior?.isDraggable = true
                }

                override fun onTabUnselected(tab: TabLayout.Tab?) {
                    when (tab?.position) {
                        0 -> extensionFrameLayout
                        else -> migrationFrameLayout
                    }?.binding?.recycler?.isNestedScrollingEnabled = false
                    if (tab?.position == 1) {
                        presenter.deselectSource()
                    }
                }

                override fun onTabReselected(tab: TabLayout.Tab?) {
                    isExpanding = !sheetBehavior.isExpanded()
                    this@ExtensionBottomSheet.sheetBehavior?.expand()
                    when (tab?.position) {
                        0 -> extensionFrameLayout
                        else -> migrationFrameLayout
                    }?.binding?.recycler?.isNestedScrollingEnabled = true
                    sheetBehavior?.isDraggable = true
                    if (!isExpanding) {
                        when (tab?.position) {
                            0 -> extensionFrameLayout
                            else -> migrationFrameLayout
                        }?.binding?.recycler?.smoothScrollToTop()
                    }
                }
            },
        )
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
        return "TabbedRecycler${binding.pager.currentItem}" == view.tag
    }

    fun updatedNestedRecyclers() {
        listOf(extensionFrameLayout, migrationFrameLayout).forEachIndexed { index, recyclerWithScrollerBinding ->
            recyclerWithScrollerBinding?.binding?.recycler?.isNestedScrollingEnabled = binding.pager.currentItem == index
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
            else -> {
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
        migAdapter?.updateDataSet(sources, changingAdapters)
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
        migAdapter?.updateDataSet(manga, changingAdapters)
        controller.updateTitleAndMenu()
    }

    fun drawExtensions() {
        if (controller.extQuery.isNotBlank()) {
            extAdapter?.updateDataSet(
                extensions.filter {
                    it.extension.name.contains(controller.extQuery, ignoreCase = true)
                },
            )
        } else {
            extAdapter?.updateDataSet(extensions)
        }
        updateExtTitle()
        updateExtUpdateAllButton()
    }

    fun canStillGoBack(): Boolean {
        return (binding.tabs.selectedTabPosition == 1 && migAdapter is MangaAdapter) ||
            (binding.tabs.selectedTabPosition == 0 && binding.sheetToolbar.hasExpandedActionView())
    }

    fun canGoBack(): Boolean {
        return if (binding.tabs.selectedTabPosition == 1 && migAdapter is MangaAdapter) {
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
            // Disable view pool recycling: position 0 (Extensions) and position 1 (Migration)
            // use structurally different views (wrapper with sub-tabs vs. bare RecyclerWithScroller),
            // and the parent's pool would otherwise hand a wrapper view to position 1.
            recycle = false
        }

        override fun getCount(): Int {
            return 2
        }

        override fun getPageTitle(position: Int): CharSequence {
            return context.getString(
                when (position) {
                    0 -> MR.strings.extensions
                    else -> MR.strings.migration
                },
            )
        }

        // Override the position-aware factory directly so position 0 returns the Extensions
        // wrapper (Manga / Light novels nested sub-tabs) and position 1 returns the existing
        // bare RecyclerWithScrollerView for Migration.
        override fun createView(container: ViewGroup, position: Int): View {
            val view = when (position) {
                0 -> createExtensionsView(container)
                else -> createView(container)
            }
            bindView(view, position)
            return view
        }

        /**
         * Migration tab page: existing bare [RecyclerWithScrollerView] layout, bit-identical.
         * Also used as a defensive default (parent's abstract requires this signature) — never
         * called for position 0 because [createView] above intercepts.
         */
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

        /**
         * Phase 8 follow-up CR6: Extensions tab page wraps the existing
         * [RecyclerWithScrollerView] in a [LinearLayout] with an inner [TabLayout] (Manga /
         * Light novels) + a [FrameLayout] hosting the manga RV and a [ComposeView] for the LN
         * aggregate plugin list.
         *
         * The inner RV keeps its tag (`TabbedRecycler0`) so the existing
         * `extensionFrameLayout` getter (findViewWithTag) continues to resolve it via
         * recursion through the wrapper.
         *
         * The LN [ComposeView] mounts [LnPluginBrowseContent] inside [YokaiTheme]; lifecycle
         * teardown is handled by `ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed`
         * mirroring [BaseComposeController].
         */
        private fun createExtensionsView(container: ViewGroup): View {
            val inflater = LayoutInflater.from(container.context)
            val wrapper = inflater.inflate(
                eu.kanade.tachiyomi.R.layout.extensions_tab_with_subtabs,
                container,
                false,
            ) as android.widget.LinearLayout

            val innerTabs = wrapper.findViewById<TabLayout>(eu.kanade.tachiyomi.R.id.inner_tabs)
            val composeView = wrapper.findViewById<androidx.compose.ui.platform.ComposeView>(
                eu.kanade.tachiyomi.R.id.ln_compose,
            )
            // The included recycler_with_scroller layout exposes its root via the recycler_layout
            // id (defined in recycler_with_scroller.xml). Find the inner RV through that.
            val innerRv = wrapper.findViewById<RecyclerWithScrollerView>(
                eu.kanade.tachiyomi.R.id.recycler_layout,
            )
            val innerBinding = RecyclerWithScrollerBinding.bind(innerRv)
            val height = this@ExtensionBottomSheet.controller.activityBinding?.bottomNav?.height
                ?: innerRv.rootWindowInsetsCompat?.getInsets(systemBars())?.bottom ?: 0
            innerRv.setUp(this@ExtensionBottomSheet, innerBinding, height)

            innerTabs.addTab(innerTabs.newTab().setText(context.getString(MR.strings.manga)))
            innerTabs.addTab(innerTabs.newTab().setText(context.getString(MR.strings.light_novels)))
            innerTabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    when (tab.position) {
                        0 -> {
                            innerRv.visibility = View.VISIBLE
                            composeView.visibility = View.GONE
                        }
                        else -> {
                            innerRv.visibility = View.GONE
                            composeView.visibility = View.VISIBLE
                            // Lazy-mount the Compose surface on first LN sub-tab selection so the
                            // WebView (LnPluginHost) doesn't spin up until needed.
                            if (composeView.tag != COMPOSE_MOUNTED_TAG) {
                                composeView.setViewCompositionStrategy(
                                    androidx.compose.ui.platform.ViewCompositionStrategy
                                        .DisposeOnViewTreeLifecycleDestroyed,
                                )
                                composeView.setContent {
                                    yokai.presentation.theme.YokaiTheme {
                                        yokai.presentation.extension.browse.LnPluginBrowseContent()
                                    }
                                }
                                composeView.tag = COMPOSE_MOUNTED_TAG
                            }
                        }
                    }
                }

                override fun onTabUnselected(tab: TabLayout.Tab) {}
                override fun onTabReselected(tab: TabLayout.Tab) {}
            })

            return wrapper
        }

        /**
         * Binds a view with a position.
         *
         * @param view the view to bind.
         * @param position the position in the adapter.
         */
        override fun bindView(view: View, position: Int) {
            val rv = innerRecyclerOf(view, position) ?: return
            rv.onBind(adapters[position]!!)
            rv.tag = "TabbedRecycler$position"
            boundViews.add(rv)
        }

        /**
         * Recycles a view.
         *
         * @param view the view to recycle.
         * @param position the position in the adapter.
         */
        override fun recycleView(view: View, position: Int) {
            val rv = innerRecyclerOf(view, position) ?: return
            boundViews.remove(rv)
        }

        /**
         * Returns the position of the view.
         */
        override fun getItemPosition(obj: Any): Int {
            // `obj` is the view returned from createView — for position 0 that's the wrapper
            // LinearLayout (find the RV inside); for position 1 it's the RV directly.
            val rv = when (obj) {
                is RecyclerWithScrollerView -> obj
                is View -> obj.findViewById<RecyclerWithScrollerView>(eu.kanade.tachiyomi.R.id.recycler_layout)
                else -> null
            } ?: return POSITION_NONE
            val index = adapters.indexOfFirst { it == rv.binding?.recycler?.adapter }
            return if (index == -1) POSITION_NONE else index
        }

        /**
         * Resolve the inner [RecyclerWithScrollerView] for a given page view. Position 0
         * returns the wrapper's inner RV (via the recycler_layout id from the included
         * recycler_with_scroller layout); position 1 returns the view as-is.
         */
        private fun innerRecyclerOf(view: View, position: Int): RecyclerWithScrollerView? = when (position) {
            0 -> view.findViewById(eu.kanade.tachiyomi.R.id.recycler_layout)
            else -> view as? RecyclerWithScrollerView
        }
    }

    companion object {
        private const val COMPOSE_MOUNTED_TAG = "compose_mounted"
    }
}
