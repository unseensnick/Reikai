package reikai.presentation.reader.text

import android.graphics.Bitmap
import android.text.Spanned
import android.text.style.ImageSpan
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import reikai.presentation.reader.WebViewHostActivity
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * That an image landing in the native renderer is drawn at its own height, with text selection on and
 * off. The layout the picture lands in was measured against the 200dp placeholder, and neither layout
 * re-reads a drawable's bounds on its own: a `PrecomputedText` caches the measurement it was built
 * from, and the `DynamicLayout` a selectable view uses reflows only on a text or span edit, while
 * `TextView.onMeasure` keeps an existing layout while the width is unchanged. One rule, so one case
 * over both modes rather than a pair that can drift.
 */
@RunWith(Parameterized::class)
class NovelImageLayoutTest(private val selectable: Boolean) {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private lateinit var block: ChapterTextBlock
    private lateinit var renderer: NovelTextRenderer
    private lateinit var server: PngServer
    private val scope = MainScope()

    @Before
    fun setUp() {
        server = PngServer(tallPng())
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { activity ->
            block = ChapterTextBlock(activity) {
                TextView(activity).apply {
                    setTextIsSelectable(selectable)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
            }
            renderer = NovelTextRenderer(activity, scope)
            activity.setContentView(block.container)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (::server.isInitialized) server.close()
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun aLoadedImageIsDrawnAtItsOwnHeight() {
        runBlocking(Dispatchers.Main) {
            renderer.render(
                block = block,
                html = "<img src=\"${server.url}\">" + "<p>lorem ipsum</p>".repeat(20),
                fontSize = 18,
                paragraphSpacing = 0f,
                paragraphIndent = 0f,
                selectable = selectable,
                bionic = false,
                contentWidth = COLUMN_PX,
                baseUrl = null,
                onTextSet = {},
            ).join()
        }
        awaitWhile { block.imagesLoading }

        val picture = measure().first
        // Without this the case would pass on an image that never arrived: the placeholder's line is
        // as tall as the placeholder, which is what the layout was measured against in the first place.
        assertTrue("the image is still ${picture}px, the placeholder's own height", picture > placeholderPx() + 1)

        // The rebuild that follows the load runs off the main thread on the precomputed branch.
        awaitWhile { abs(measure().second - picture) > SLACK_PX }
        val line = measure().second
        assertEquals(
            "the line holding a ${picture}px picture is ${line}px tall",
            picture.toDouble(),
            line.toDouble(),
            SLACK_PX.toDouble(),
        )
    }

    /** The image span's drawable height and the height of the line holding it, both in pixels. */
    private fun measure(): Pair<Int, Int> {
        var picture = 0
        var line = 0
        instrumentation.runOnMainSync {
            block.chunkViews.forEach { view ->
                val text = view.text as? Spanned ?: return@forEach
                val span = text.getSpans(0, text.length, ImageSpan::class.java).firstOrNull() ?: return@forEach
                picture = span.drawable.bounds.height()
                val layout = view.layout ?: return@forEach
                val index = layout.getLineForOffset(text.getSpanStart(span))
                line = layout.getLineBottom(index) - layout.getLineTop(index)
            }
        }
        return picture to line
    }

    private fun placeholderPx(): Int =
        (PLACEHOLDER_DP * instrumentation.targetContext.resources.displayMetrics.density).toInt()

    private fun awaitWhile(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(TIMEOUT_S)
        while (condition() && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    private fun tallPng(): ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(400, 2000, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    /**
     * Serves [png] over loopback. The renderer fetches through the app's own Coil loader, which has no
     * seam a test can stand in at the way a WebView's client is one, so the image comes off a socket.
     */
    private class PngServer(private val png: ByteArray) : Closeable {

        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))

        val url = "http://127.0.0.1:${socket.localPort}/picture.png"

        init {
            Thread {
                while (!socket.isClosed) {
                    runCatching {
                        socket.accept().use { client ->
                            val reader = client.getInputStream().bufferedReader()
                            while (true) {
                                val line = reader.readLine() ?: break
                                if (line.isEmpty()) break
                            }
                            client.getOutputStream().apply {
                                write(
                                    (
                                        "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n" +
                                            "Content-Length: ${png.size}\r\nConnection: close\r\n\r\n"
                                        ).toByteArray(),
                                )
                                write(png)
                                flush()
                            }
                        }
                    }
                }
            }.apply { isDaemon = true }.start()
        }

        override fun close() = socket.close()
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "selectable={0}")
        fun modes(): List<Boolean> = listOf(false, true)

        /** Narrower than any test device, so the fitted picture is taller than the placeholder. */
        const val COLUMN_PX = 600

        /** NovelImageGetter's own placeholder height. */
        const val PLACEHOLDER_DP = 200

        /** A line's own leading, which an image span's line does not add but the framework may round. */
        const val SLACK_PX = 2

        const val TIMEOUT_S = 15L
    }
}
