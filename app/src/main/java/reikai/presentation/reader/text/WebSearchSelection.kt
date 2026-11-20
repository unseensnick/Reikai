package reikai.presentation.reader.text

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR

/**
 * Adds Web search to a chapter text's selection menu, for a word no dictionary app knows, as the WebView mode's
 * own menu has it. Offered only when an app answers a web search, as there, so it never does nothing.
 */
internal class WebSearchSelection(private val view: TextView) : ActionMode.Callback {

    override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        if (webSearch("").resolveActivity(view.context.packageManager) != null) {
            val title = view.context.stringResource(MR.strings.action_web_search)
            menu.add(Menu.NONE, ITEM, Menu.CATEGORY_SECONDARY, title)
        }
        return true
    }

    override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

    override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
        if (item.itemId != ITEM) return false
        val start = minOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
        val end = maxOf(view.selectionStart, view.selectionEnd).coerceAtLeast(0)
        try {
            view.context.startActivity(webSearch(view.text.substring(start, end)))
        } catch (_: ActivityNotFoundException) {
            // The app that answered the menu's check went away since
        }
        mode.finish()
        return true
    }

    override fun onDestroyActionMode(mode: ActionMode) = Unit

    private fun webSearch(query: String) =
        Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        val ITEM = View.generateViewId()
    }
}
