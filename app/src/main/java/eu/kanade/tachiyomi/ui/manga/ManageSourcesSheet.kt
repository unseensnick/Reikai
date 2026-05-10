package eu.kanade.tachiyomi.ui.manga

import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.ui.base.controller.DialogController
import eu.kanade.tachiyomi.util.system.dpToPx
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
    private var onConfirmSplit: (List<Long>) -> Unit = {}
    private var onConfirmRemoveFromLibrary: (List<Long>) -> Unit = {}

    // Required by Conductor for state restoration
    @Suppress("unused")
    constructor() : super()

    constructor(
        presenter: MangaDetailsPresenter,
        onConfirmSplit: (List<Long>) -> Unit = {},
        onConfirmRemoveFromLibrary: (List<Long>) -> Unit = {},
    ) : super() {
        this.presenter = presenter
        this.onConfirmSplit = onConfirmSplit
        this.onConfirmRemoveFromLibrary = onConfirmRemoveFromLibrary
    }

    private var sourcesContainer: LinearLayout? = null
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
                layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, p8)
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
        val checkbox = CheckBox(ctx).apply {
            isChecked = mangaId in selectedIds
            isClickable = false
            isFocusable = false
            contentDescription = source.name
            layoutParams = FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                marginEnd = p8
            }
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) selectedIds.add(mangaId) else selectedIds.remove(mangaId)
                updateActionButtonsState()
            }
        }
        return FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            isClickable = true
            isFocusable = true
            val ripple = TypedValue().also {
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
            }
            setBackgroundResource(ripple.resourceId)
            setOnClickListener { checkbox.toggle() }
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
            addView(checkbox)
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
                onConfirmSplit(ids)
                dismissDialog()
            }
            .setNegativeButton(AR.string.cancel, null)
            .show()
    }

    private fun onRemoveFromLibraryClicked(ctx: Context) {
        if (selectedIds.isEmpty()) return
        val ids = selectedIds.toList()
        ctx.materialAlertDialog()
            .setMessage(
                ctx.getString(MR.strings.remove_from_library) + "?\n" + selectedNamesText(),
            )
            .setPositiveButton(AR.string.ok) { _, _ ->
                onConfirmRemoveFromLibrary(ids)
                dismissDialog()
            }
            .setNegativeButton(AR.string.cancel, null)
            .show()
    }

}
