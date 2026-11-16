package reikai.presentation.reader

import android.graphics.Paint
import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import eu.kanade.tachiyomi.ui.reader.viewer.LocalPageColorFilter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ViewportHostTest {

    @get:Rule
    val compose = createComposeRule()

    /** A page turn lays its copy of the filtered screen beside the view, where a layer would filter it again. */
    @Test
    fun `the page filter sits on the view and not on what a page turn's copy shares with it`() {
        val view = View(ApplicationProvider.getApplicationContext())

        compose.setContent {
            CompositionLocalProvider(LocalPageColorFilter provides ColorFilter.colorMatrix(ColorMatrix())) {
                ViewportHost(view)
            }
        }
        compose.waitForIdle()

        view.layerType shouldBe View.LAYER_TYPE_HARDWARE
        (view.parent as View).layerType shouldBe View.LAYER_TYPE_NONE
    }

    @Test
    fun `the view's layer draws through the page filter`() {
        val view = LayerRecordingView()

        compose.setContent {
            CompositionLocalProvider(LocalPageColorFilter provides ColorFilter.colorMatrix(ColorMatrix())) {
                ViewportHost(view)
            }
        }
        compose.waitForIdle()

        view.handedPaint?.colorFilter shouldNotBe null
    }

    /** A layer renders the scrolling page offscreen every frame, so a page with no filter has none. */
    @Test
    fun `a filter turned off takes the view's layer away`() {
        val view = View(ApplicationProvider.getApplicationContext())
        var filter by mutableStateOf<ColorFilter?>(ColorFilter.colorMatrix(ColorMatrix()))

        compose.setContent {
            CompositionLocalProvider(LocalPageColorFilter provides filter) {
                ViewportHost(view)
            }
        }
        compose.waitForIdle()
        filter = null
        compose.waitForIdle()

        view.layerType shouldBe View.LAYER_TYPE_NONE
    }

    /** The platform keeps a layer's paint to itself, so the one handed over is recorded here. */
    private class LayerRecordingView : View(ApplicationProvider.getApplicationContext()) {
        var handedPaint: Paint? = null

        override fun setLayerType(layerType: Int, paint: Paint?) {
            handedPaint = paint
            super.setLayerType(layerType, paint)
        }
    }
}
