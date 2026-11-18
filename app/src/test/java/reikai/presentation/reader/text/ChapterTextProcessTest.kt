package reikai.presentation.reader.text

import android.app.Activity
import android.provider.Settings
import android.text.Selection
import android.text.Spannable
import android.widget.TextView
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/** A word selected in the reader can go to another app's text action, a dictionary or a translator. */
@RunWith(RobolectricTestRunner::class)
class ChapterTextProcessTest {

    @Test
    fun `a selected word in a chunk offers other apps' text actions`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // A set-up phone; Robolectric starts unprovisioned, which hides the text actions on any view.
        Settings.Global.putInt(activity.contentResolver, Settings.Global.DEVICE_PROVISIONED, 1)
        val block = ChapterTextBlock(activity) {
            TextView(activity).apply {
                setTextIsSelectable(true)
                text = "wonderful"
            }
        }
        block.ensureChunkCount(1)
        val chunk = block.chunkViews.single()
        Selection.setSelection(chunk.text as Spannable, 0, chunk.text.length)

        canProcessText(chunk) shouldBe true
    }

    /** The framework's own gate on the selection menu's text actions, which it keeps package-private. */
    private fun canProcessText(view: TextView): Boolean =
        TextView::class.java.getDeclaredMethod("canProcessText")
            .apply { isAccessible = true }
            .invoke(view) as Boolean
}
