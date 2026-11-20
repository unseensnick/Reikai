package reikai.presentation.reader.text

import android.app.Activity
import android.app.SearchManager
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import android.text.Selection
import android.text.Spannable
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** A selected word can be searched on the web, from the menu, when an app answers web searches. */
@RunWith(RobolectricTestRunner::class)
class WebSearchSelectionTest {

    private val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
    private val view = TextView(activity).apply {
        setTextIsSelectable(true)
        text = "a wonderful word"
        Selection.setSelection(text as Spannable, 2, 11)
    }
    private val added = slot<Int>()
    private val menu = mockk<Menu> {
        every { add(any<Int>(), capture(added), any<Int>(), any<CharSequence>()) } returns mockk()
    }
    private val mode = mockk<ActionMode>(relaxUnitFun = true)

    @Test
    fun `with a search app the menu's Web search searches the selected word`() {
        searchApp()
        val callback = WebSearchSelection(view)
        callback.onCreateActionMode(mode, menu)

        callback.onActionItemClicked(mode, mockk<MenuItem> { every { itemId } returns added.captured })

        shadowOf(activity).nextStartedActivity.let { it.action to it.getStringExtra(SearchManager.QUERY) } shouldBe
            (Intent.ACTION_WEB_SEARCH to "wonderful")
    }

    @Test
    fun `with no search app the menu offers no Web search`() {
        WebSearchSelection(view).onCreateActionMode(mode, menu)

        added.isCaptured shouldBe false
    }

    private fun searchApp() {
        shadowOf(activity.packageManager).addResolveInfoForIntent(
            Intent(Intent.ACTION_WEB_SEARCH),
            ResolveInfo().apply {
                activityInfo = ActivityInfo().apply {
                    packageName = "search.app"
                    name = "Search"
                }
            },
        )
    }
}
