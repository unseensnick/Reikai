package eu.kanade.tachiyomi.ui.manga

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.view.setPadding
import androidx.core.widget.NestedScrollView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.materialAlertDialog
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yokai.i18n.MR
import yokai.util.lang.getString
import android.R as AR

class ManageSourcesSheet : DialogController {
    private lateinit var presenter: MangaDetailsPresenter
    private var onSourceRemoved: () -> Unit = {}

    // Required by Conductor for state restoration
    @Suppress("unused")
    constructor() : super()

    constructor(presenter: MangaDetailsPresenter, onSourceRemoved: () -> Unit = {}) : super() {
        this.presenter = presenter
        this.onSourceRemoved = onSourceRemoved
    }

    private var sourcesContainer: LinearLayout? = null
    private var searchResultsRecycler: RecyclerView? = null
    private var searchResultsAdapter: SearchResultAdapter? = null
    private var splitButton: MaterialButton? = null
    private var removeButton: MaterialButton? = null

    private val selectedIds = mutableSetOf<Long>()
    private val sourceNamesById = mutableMapOf<Long, String>()

    override fun onCreateDialog(savedViewState: Bundle?): Dialog {
        val ctx = activity!!
        val dialog = BottomSheetDialog(ctx)
        val scrollView = NestedScrollView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        }
        scrollView.addView(buildContentView(ctx))
        dialog.setContentView(scrollView)
        return dialog
    }

    override fun onDestroyView(view: View) {
        onCreateViewScope?.cancel()
        super.onDestroyView(view)
    }

    override fun onAttach(view: View) {
        super.onAttach(view)
        if (!::presenter.isInitialized) {
            dismissDialog()
            return
        }
        loadSources()
    }

    private fun buildContentView(ctx: Context): LinearLayout {
        val p16 = 16.dpToPx
        val p8 = 8.dpToPx
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(TextView(ctx).apply {
                text = ctx.getString(MR.strings.manage_sources)
                textSize = 18f
                setTypeface(null, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    setMargins(p16, p16, p16, p8)
                }
            })
            sourcesContainer = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            addView(sourcesContainer)
            addView(buildActionBar(ctx, p16, p8))
            addView(View(ctx).apply {
                setBackgroundColor(ctx.getResourceColor(R.attr.colorOutline))
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, 1.dpToPx).apply {
                    setMargins(0, p8, 0, p8)
                }
            })
            addView(TextView(ctx).apply {
                text = ctx.getString(MR.strings.add_another_manga)
                textSize = 14f
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    setMargins(p16, 0, p16, p8)
                }
            })
            val searchField = EditText(ctx).apply {
                hint = ctx.getString(MR.strings.search)
                setSingleLine(true)
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    setMargins(p16, 0, p16, 0)
                }
            }
            addView(searchField)
            searchResultsAdapter = SearchResultAdapter { mangaId ->
                presenter.addToGroup(mangaId)
                dismissDialog()
            }
            searchResultsRecycler = RecyclerView(ctx).apply {
                layoutManager = LinearLayoutManager(ctx)
                adapter = searchResultsAdapter
                isVisible = false
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            addView(searchResultsRecycler)
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, p16)
            })
            searchField.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable) {
                    val query = s.toString().trim()
                    if (query.length >= 2) {
                        onCreateViewScope?.launch {
                            val results = withContext(Dispatchers.IO) {
                                presenter.searchAddableManga(query)
                            }
                            searchResultsAdapter?.updateResults(results)
                            searchResultsRecycler?.isVisible = results.isNotEmpty()
                        }
                    } else {
                        searchResultsAdapter?.updateResults(emptyList())
                        searchResultsRecycler?.isVisible = false
                    }
                }
            })
        }
    }

    private fun buildActionBar(ctx: Context, p16: Int, p8: Int): View {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                setMargins(p16, p8, p16, p8)
            }
            splitButton = MaterialButton(
                ctx,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle,
            ).apply {
                text = ctx.getString(MR.strings.split_selected_sources)
                isEnabled = false
                setOnClickListener { onSplitClicked(ctx) }
            }
            addView(splitButton)
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(p8, WRAP_CONTENT)
            })
            removeButton = MaterialButton(ctx).apply {
                text = ctx.getString(MR.strings.remove_selected_from_library)
                isEnabled = false
                setOnClickListener { onRemoveFromLibraryClicked(ctx) }
            }
            addView(removeButton)
        }
    }

    private fun loadSources() {
        onCreateViewScope?.launch {
            val sources = withContext(Dispatchers.IO) { presenter.availableSources() }
            updateSourcesUI(sources)
        }
    }

    private fun updateSourcesUI(sources: List<Pair<Long, Source>>) {
        val container = sourcesContainer ?: return
        val ctx = activity ?: return
        container.removeAllViews()
        selectedIds.clear()
        sourceNamesById.clear()
        updateActionButtonsState()
        val p16 = 16.dpToPx
        val p12 = 12.dpToPx
        val p8 = 8.dpToPx
        if (sources.isEmpty()) {
            container.addView(TextView(ctx).apply {
                text = ctx.getString(MR.strings.remove_from_group)
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    setMargins(p16, p8, p16, p8)
                }
            })
            splitButton?.isVisible = false
            removeButton?.isVisible = false
            return
        }
        splitButton?.isVisible = true
        removeButton?.isVisible = true
        for ((mangaId, source) in sources) {
            sourceNamesById[mangaId] = source.name
            container.addView(
                buildSourceRow(ctx, mangaId, source, mangaId == presenter.mangaId, p16, p12, p8),
            )
        }
    }

    private fun buildSourceRow(
        ctx: Context,
        mangaId: Long,
        source: Source,
        isCurrent: Boolean,
        p16: Int,
        p12: Int,
        p8: Int,
    ): View {
        return FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(TextView(ctx).apply {
                text = buildString { append(source.name); if (isCurrent) append("  ✓") }
                textSize = 15f
                if (isCurrent) setTypeface(null, Typeface.BOLD)
                layoutParams = FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_VERTICAL
                    marginStart = p16
                    marginEnd = 56.dpToPx
                    topMargin = p12
                    bottomMargin = p12
                }
            })
            addView(CheckBox(ctx).apply {
                isChecked = mangaId in selectedIds
                contentDescription = source.name
                layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_VERTICAL or Gravity.END
                    marginEnd = p8
                }
                setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) selectedIds.add(mangaId) else selectedIds.remove(mangaId)
                    updateActionButtonsState()
                }
            })
        }
    }

    private fun updateActionButtonsState() {
        val anySelected = selectedIds.isNotEmpty()
        splitButton?.isEnabled = anySelected
        removeButton?.isEnabled = anySelected
    }

    private fun selectedNamesText(): String =
        selectedIds.mapNotNull { sourceNamesById[it] }.joinToString("\n")

    private fun onSplitClicked(ctx: Context) {
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toList()
        ctx.materialAlertDialog()
            .setMessage(
                ctx.getString(MR.strings.remove_from_group) + "?\n" + selectedNamesText(),
            )
            .setPositiveButton(AR.string.ok) { _, _ ->
                presenter.removeFromGroup(ids)
                loadSources()
                onSourceRemoved()
            }
            .setNegativeButton(AR.string.cancel, null)
            .show()
    }

    private fun onRemoveFromLibraryClicked(ctx: Context) {
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toList()
        val containsCurrent = presenter.mangaId in ids
        ctx.materialAlertDialog()
            .setMessage(
                ctx.getString(MR.strings.remove_from_library) + "?\n" + selectedNamesText(),
            )
            .setPositiveButton(AR.string.ok) { _, _ ->
                onCreateViewScope?.launch {
                    presenter.removeFromLibrary(ids)
                    if (containsCurrent) {
                        dismissDialog()
                    } else {
                        loadSources()
                    }
                    onSourceRemoved()
                }
            }
            .setNegativeButton(AR.string.cancel, null)
            .show()
    }

    private inner class SearchResultAdapter(
        private val onSelect: (Long) -> Unit,
    ) : RecyclerView.Adapter<SearchResultAdapter.ViewHolder>() {
        private var items: List<Pair<Long, String>> = emptyList()

        fun updateResults(newItems: List<Pair<Long, String>>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val tv = TextView(parent.context).apply {
                textSize = 14f
                setPadding(16.dpToPx)
                layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            }
            return ViewHolder(tv)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val (mangaId, label) = items[position]
            holder.textView.text = label
            holder.textView.setOnClickListener { onSelect(mangaId) }
        }

        override fun getItemCount() = items.size

        inner class ViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)
    }
}
