package eu.kanade.tachiyomi.ui.manga.related.browse

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.recyclerview.widget.GridLayoutManager
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import eu.davidea.flexibleadapter.FlexibleAdapter
import eu.davidea.flexibleadapter.SelectableAdapter
import eu.davidea.flexibleadapter.items.IFlexible
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.recommendation.RECOMMENDS_SOURCE
import eu.kanade.tachiyomi.databinding.RelatedMangasBrowseControllerBinding
import eu.kanade.tachiyomi.ui.base.SmallToolbarInterface
import eu.kanade.tachiyomi.ui.base.controller.BaseLegacyController
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.manga.related.RelatedMangaCandidate
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.util.system.launchUI
import eu.kanade.tachiyomi.util.view.liftAppbarWith
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import uy.kohesive.injekt.injectLazy
import yokai.i18n.MR
import yokai.util.lang.getString

/**
 * Phase 6.5 — full-screen "See all" browse view for related mangas. Shows the unbounded ranked
 * pool from [eu.kanade.tachiyomi.ui.manga.MangaDetailsPresenter.relatedMangasFullPool], grid-laid
 * out via [eu.kanade.tachiyomi.widget.AutofitRecyclerView]. Selection mode enters on long-press
 * (mirrors `LibraryController`); the action toolbar exposes Add to library + Select all + Invert.
 *
 * The pool is handed off via [RelatedMangasHandoff] keyed by `mangaId`. Bundle carries only the id
 * — [eu.kanade.tachiyomi.source.model.SManga] is `java.io.Serializable` and lives in the plugin
 * contract (`source/api`), so we can neither `@Parcelize` it nor pay the cost of Serializable
 * Bundle round-tripping ~150 entries.
 *
 * Process-death recovery: if the singleton's map is wiped between handoff write and read (process
 * kill while the browse view is on top), the grid renders empty and the user backs out manually.
 * Auto-popping during view creation races the in-progress transition.
 */
class RelatedMangasBrowseController(bundle: Bundle) :
    BaseLegacyController<RelatedMangasBrowseControllerBinding>(bundle),
    SmallToolbarInterface,
    FlexibleAdapter.OnItemClickListener,
    FlexibleAdapter.OnItemLongClickListener,
    ActionMode.Callback {

    constructor(mangaId: Long) : this(
        Bundle().apply { putLong(MANGA_ID_KEY, mangaId) },
    )

    init {
        setHasOptionsMenu(true)
    }

    private val handoff: RelatedMangasHandoff by injectLazy()
    private val mangaId: Long = args.getLong(MANGA_ID_KEY)

    private var pool: List<RelatedMangaCandidate> = emptyList()
    private var adapter: FlexibleAdapter<IFlexible<*>>? = null
    private var actionMode: ActionMode? = null
    private val bulkAddHandler = BulkAddToLibraryHandler()

    override fun createBinding(inflater: LayoutInflater) =
        RelatedMangasBrowseControllerBinding.inflate(inflater)

    override fun getTitle(): String? =
        view?.context?.getString(MR.strings.related_mangas_browse_title)

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        liftAppbarWith(binding.recycler)

        // Read-and-clear the handoff here (not in onChangeStarted) — Conductor fires
        // postCreateView → onViewCreated BEFORE onChangeStarted for the entering controller
        // during a push, so onChangeStarted is too late. If the take returns empty (process
        // death between handoff write and pickup), the grid renders empty and the user backs
        // out; the carousel re-fetches on next open.
        if (pool.isEmpty()) {
            pool = handoff.take(mangaId).orEmpty()
        }

        val recycler = binding.recycler
        recycler.layoutManager = GridLayoutManager(view.context, computeSpanCount(view))
        recycler.setHasFixedSize(true)

        val items: List<IFlexible<*>> = pool.map {
            RelatedMangaBrowseItem(it.sourceId, it.trackerName, it.manga)
        }
        adapter = FlexibleAdapter<IFlexible<*>>(items, this, true).apply {
            mode = SelectableAdapter.Mode.IDLE
        }
        recycler.adapter = adapter
    }

    /**
     * Span count scaled to the screen width so foldables / tablets don't waste space with
     * oversized cards. Each carousel card is ~120dp wide (112dp card + 4dp lateral margins
     * from `related_manga_card_item.xml`); we divide by ~130dp to leave a touch of breathing
     * room and floor at 3 so phones stay consistent with the carousel rhythm.
     */
    private fun computeSpanCount(view: View): Int {
        val widthDp = view.resources.configuration.screenWidthDp
        return (widthDp / 130).coerceAtLeast(3)
    }

    override fun onDestroyView(view: View) {
        actionMode?.finish()
        adapter = null
        super.onDestroyView(view)
    }

    // region click / long-press

    override fun onItemClick(view: View?, position: Int): Boolean {
        val adapter = adapter ?: return false
        if (adapter.mode == SelectableAdapter.Mode.MULTI) {
            adapter.toggleSelection(position)
            if (adapter.selectedItemCount == 0) {
                actionMode?.finish()
            } else {
                actionMode?.invalidate()
            }
            adapter.notifyItemChanged(position)
            return true
        }
        val item = adapter.getItem(position) as? RelatedMangaBrowseItem ?: return false
        openManga(item)
        return false
    }

    override fun onItemLongClick(position: Int) {
        val adapter = adapter ?: return
        if (adapter.mode != SelectableAdapter.Mode.MULTI) {
            adapter.mode = SelectableAdapter.Mode.MULTI
            // Toggle the long-pressed item BEFORE starting the action mode — Android's
            // startSupportActionMode calls onCreateActionMode + onPrepareActionMode
            // synchronously, and if selection is still empty at that point the prepare
            // callback can't finish() the mode without crashing through
            // onSupportActionModeFinished(null). Selecting first guarantees a non-empty count.
            adapter.toggleSelection(position)
            adapter.notifyItemChanged(position)
            actionMode = (activity as? AppCompatActivity)?.startSupportActionMode(this)
            return
        }
        adapter.toggleSelection(position)
        adapter.notifyItemChanged(position)
        if (adapter.selectedItemCount == 0) {
            actionMode?.finish()
        } else {
            actionMode?.invalidate()
        }
    }

    private fun openManga(item: RelatedMangaBrowseItem) {
        if (item.sourceId == RECOMMENDS_SOURCE) {
            router.pushController(GlobalSearchController(item.manga.title).withFadeTransaction())
            return
        }
        launchUI {
            // Resolve via MangaDetailsPresenter.toLocalManga semantics inline to avoid coupling
            // a presenter dependency into the browse view. Mirrors what the carousel tap does.
            val resolver = MangaResolver
            val local = resolver.resolve(item.sourceId, item.manga) ?: return@launchUI
            router.pushController(MangaDetailsController(local, true).withFadeTransaction())
        }
    }

    // endregion

    // region action mode

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        mode.menuInflater.inflate(R.menu.related_mangas_browse_selection, menu)
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
        val adapter = adapter ?: return false
        val count = adapter.selectedItemCount
        // Don't auto-finish the mode here on count==0 — onPrepareActionMode is invoked
        // synchronously from startSupportActionMode before we've had a chance to toggle the
        // long-pressed item, and calling finish() during that re-entrancy crashes through
        // MainActivity.onSupportActionModeFinished(null). The explicit empty-check in
        // onItemClick handles the post-toggle empty case.
        mode.title = view?.context?.getString(MR.strings.selected_, count)
        return false
    }

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        val adapter = adapter ?: return false
        return when (item.itemId) {
            R.id.action_select_all -> {
                adapter.selectAll()
                adapter.notifyDataSetChanged()
                    mode.invalidate()
                true
            }
            R.id.action_invert_selection -> {
                val total = adapter.itemCount
                for (i in 0 until total) {
                    adapter.toggleSelection(i)
                }
                adapter.notifyDataSetChanged()
                    if (adapter.selectedItemCount == 0) {
                    mode.finish()
                } else {
                    mode.invalidate()
                }
                true
            }
            R.id.action_add_to_library -> {
                val selected = adapter.selectedPositions
                    .mapNotNull { adapter.getItem(it) as? RelatedMangaBrowseItem }
                    .map { RelatedMangaCandidate(it.sourceId, it.trackerName, it.manga) }
                val activity = activity ?: return false
                launchUI {
                    bulkAddHandler.bulkAdd(
                        activity = activity,
                        candidates = selected,
                        resolveLocal = { sManga, sourceId ->
                            MangaResolver.resolve(sourceId, sManga)
                        },
                        onDone = {
                            adapter.clearSelection()
                            adapter.notifyDataSetChanged()
                            mode.finish()
                        },
                    )
                }
                true
            }
            else -> false
        }
    }

    override fun onDestroyActionMode(mode: ActionMode) {
        adapter?.let {
            it.mode = SelectableAdapter.Mode.IDLE
            it.clearSelection()
            it.notifyDataSetChanged()
        }
        actionMode = null
    }

    // endregion

    override fun onChangeEnded(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeEnded(handler, type)
        if (type == ControllerChangeType.POP_EXIT) {
            actionMode?.finish()
        }
    }

    companion object {
        const val MANGA_ID_KEY = "RelatedMangasBrowseController.mangaId"
    }
}
