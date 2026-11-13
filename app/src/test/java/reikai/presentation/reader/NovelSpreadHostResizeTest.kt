package reikai.presentation.reader

import android.view.View
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLooper

/** A spread tells its renderer once it has a size, since only then does the gap's share of the height hold. */
@RunWith(RobolectricTestRunner::class)
class NovelSpreadHostResizeTest {

    @Test
    fun `a spread taking its first size tells its renderer`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        var told = 0
        val host = NovelSpreadHost(context, View(context), heightFactor = 1, rtl = false) { 0 }
        host.onResized = { told++ }
        host.layout(0, 0, 2000, 1200)
        ShadowLooper.idleMainLooper()
        told shouldBe 1
    }
}
