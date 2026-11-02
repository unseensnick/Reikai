package reikai.presentation.reader

import android.view.View
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import eu.kanade.tachiyomi.ui.reader.viewer.LocalPageColorFilter
import io.kotest.matchers.shouldBe
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
}
