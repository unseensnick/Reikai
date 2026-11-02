package reikai.presentation.reader

import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import ca.mpreg.webgpuviewer.viewer.ImagePage
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuous
import ca.mpreg.webgpuviewer.viewer.ImageViewerContinuousState
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

/**
 * A strip zoomed right out with every page in reach still a Compose page, as a run of failed pages is, on
 * the library's real renderer. The capture is the size Reikai asks for on a Fold's window, which is where
 * a full-size buffer per page ran the heap out; the emulator's own window does not change it.
 */
@RunWith(AndroidJUnit4::class)
class WebGpuStripMemoryTest {

    @Test
    fun aFullReachOfChromePagesFitsAThirdOfTheHeap() {
        val state = ImageViewerContinuousState()
        val pages = List(PAGES) {
            ImagePage.Compose(stripCaptureSize(WINDOW_WIDTH, WINDOW_HEIGHT, heightFraction = 1f), 0) {
                Box(Modifier.fillMaxSize())
            }
        }
        state.fetchPage = { pages.getOrNull(it) }
        state.minScale = MIN_SCALE

        ActivityScenario.launch(WebViewHostActivity::class.java).use { scenario ->
            scenario.onActivity { it.setContent { ImageViewerContinuous(Modifier.fillMaxSize(), state) } }
            state.scale = MIN_SCALE
            val peak = peakBufferBytes(scenario)
            val limit = Runtime.getRuntime().maxMemory() / 3

            assertTrue("no capture ran, so this measured nothing", peak > 0)
            assertTrue("capture buffers peaked at $peak of $limit", peak < limit)
        }
    }

    private fun peakBufferBytes(scenario: ActivityScenario<WebViewHostActivity>): Long {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WATCH_S)
        var peak = 0L
        while (System.nanoTime() < deadline) {
            scenario.onActivity { activity ->
                host(activity.window.decorView)?.let { peak = maxOf(peak, bufferBytes(it)) }
            }
            Thread.sleep(POLL_MS)
        }
        return peak
    }

    // By name, since the library keeps the class internal
    private fun host(view: View): View? = when {
        view.javaClass.name == HOST_CLASS -> view
        view is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { host(view.getChildAt(it)) }
        else -> null
    }

    /**
     * The library's own field, since it reports no total. A rename fails the read, not the bound. Only the
     * direct buffers count: ART takes those from the Java heap, which is what ran out, where each page's
     * scratch bitmap of the same size is native memory.
     */
    private fun bufferBytes(host: View): Long {
        val renderers = host.javaClass.getDeclaredField("renderers")
            .apply { isAccessible = true }.get(host) as Map<*, *>
        return renderers.values.filterNotNull().sumOf { renderer ->
            val pixels = renderer.javaClass.getDeclaredField("pixels").apply { isAccessible = true }.get(renderer)
            ((pixels as ByteBuffer?)?.capacity() ?: 0).toLong()
        }
    }

    private companion object {
        const val HOST_CLASS = "ca.mpreg.webgpuviewer.viewer.ComposePageHost"
        const val WINDOW_WIDTH = 2256
        const val WINDOW_HEIGHT = 2504
        const val PAGES = 40
        const val MIN_SCALE = 0.04f
        const val WATCH_S = 15L
        const val POLL_MS = 100L
    }
}
