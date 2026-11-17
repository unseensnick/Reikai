package reikai.presentation.reader.text

import android.text.SpannableString
import android.text.Spanned
import android.text.style.ClickableSpan
import android.text.style.ImageSpan
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What a screen reader is handed for a chunk holding a failed picture: the accessibility node's text, which
 * is what TalkBack speaks, and the links in it, which TalkBack offers to activate.
 */
@RunWith(RobolectricTestRunner::class)
class FailedPictureAccessibilityTest {

    private fun chunk(retryLabel: String?): TextView {
        val box = DrawableWrapper().apply {
            innerDrawable = ImageFailureDrawable(100, 10f, 1f, "Couldn't load image", retryLabel) { 0 }
        }
        val text = SpannableString("Before ￼ after").apply {
            setSpan(ImageSpan(box), 7, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (retryLabel != null) setSpan(Retry, 7, 8, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return TextView(ApplicationProvider.getApplicationContext()).apply {
            this.text = text
            accessibilityDelegate = FailedPictureAccessibility
        }
    }

    @Test
    fun `a failed picture is read by its heading and Retry`() {
        chunk("Retry").createAccessibilityNodeInfo().text.toString() shouldBe "Before Couldn't load image. Retry after"
    }

    @Test
    fun `a failed picture's Retry link covers the Retry label`() {
        val read = chunk("Retry").createAccessibilityNodeInfo().text as Spanned

        val links = read.getSpans(0, read.length, ClickableSpan::class.java)

        links.map { read.substring(read.getSpanStart(it), read.getSpanEnd(it)) } shouldBe listOf("Retry")
    }

    @Test
    fun `a failed picture with no Retry is read by its heading alone`() {
        chunk(null).createAccessibilityNodeInfo().text.toString() shouldBe "Before Couldn't load image after"
    }

    private object Retry : ClickableSpan() {
        override fun onClick(widget: View) = Unit
    }
}
