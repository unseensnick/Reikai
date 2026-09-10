package reikai.presentation.reader.text

import android.text.Html
import android.text.SpannableStringBuilder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import reikai.novel.content.NovelHtmlUtils

/**
 * What a chapter's blank lines survive as in the native renderer. `Html.fromHtml` is framework code
 * with no Robolectric here, so the newline arithmetic these pin can only be measured on a device.
 *
 * The rule under test: one break per run is the block separator and goes; anything longer is line
 * breaks the source asked for and stays, which is what makes "Remove extra spacing" observable.
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class NovelBlankLineTest {

    private fun rendered(html: String): String {
        val spanned = Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY, null, null)
        return SpannableStringBuilder(spanned)
            .also { NovelTextRenderer.collapseBlankLines(it) }
            .toString()
    }

    @Test
    fun aBlockSeparatorLeavesNoBlankLine() {
        assertEquals("a\nb", rendered("<p>a</p><p>b</p>").trimEnd('\n'))
    }

    @Test
    fun breaksTheSourceAskedForSurvive() {
        val text = rendered("<p>a</p><br><br><br><br><p>b</p>").trimEnd('\n')
        assertTrue("stacked breaks were flattened: ${text.escaped()}", text.count { it == '\n' } > 1)
    }

    @Test
    fun removeExtraSpacingChangesWhatTheReaderDraws() {
        val source = "<p>a</p><br><br><br><br><p>b</p>"
        val off = rendered(source).trimEnd('\n')
        val on = rendered(NovelHtmlUtils.removeExtraParagraphSpacing(source)).trimEnd('\n')
        assertTrue(
            "the setting is inert: both render as ${off.escaped()}",
            off.count { it == '\n' } > on.count { it == '\n' },
        )
    }

    @Test
    fun aPaddedParagraphIsStillRemoved() {
        val source = "<p>a</p><p>&nbsp;</p><p>b</p>"
        val on = rendered(NovelHtmlUtils.removeExtraParagraphSpacing(source))
        assertTrue("padding survived: ${on.escaped()}", !on.contains('\u00A0'))
    }

    private fun String.escaped() = replace("\n", "\\n")
}
