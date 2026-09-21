package reikai.presentation.reader.web

import android.graphics.Bitmap
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.SourceFetchResult
import eu.kanade.tachiyomi.network.HttpException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okio.Buffer
import okio.FileSystem
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import reikai.data.coil.NovelImage
import reikai.presentation.reader.NovelChapterNavigationClient
import reikai.presentation.reader.WebViewHostActivity
import java.io.ByteArrayOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A chapter's pictures in a real WebView reach the reader's own fetch through the intercept. A served
 * picture carries no content type, as one off the disk cache does, and must still draw.
 */
@RunWith(AndroidJUnit4::class)
class NovelWebImagesInterceptTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private val fetched = CopyOnWriteArrayList<NovelImage>()
    private val scope = CoroutineScope(SupervisorJob())

    /** A 2 by 1 picture, so a drawn one is told apart from a broken one by its width. */
    private val png: ByteArray = ByteArrayOutputStream().also {
        Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it)
    }.toByteArray()

    private companion object {
        const val BASE = "https://site.example/novel/chapter-1"
        const val TIMEOUT_S = 10L
    }

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity { activity ->
            activity.webView.settings.javaScriptEnabled = true
            val images = NovelWebImages()
            activity.webView.webViewClient = NovelChapterNavigationClient(activity, { BASE }, images, scope) { image ->
                fetched += image
                if (!image.url.endsWith("ok.png")) throw HttpException(404)
                SourceFetchResult(ImageSource(Buffer().write(png), FileSystem.SYSTEM), null, DataSource.DISK)
            }
            val chapter = images.rewrite(
                """<img id="a" src="/ok.png"><img id="b" src="/gone.png">""",
                BASE,
                "p",
            )
            activity.webView.loadDataWithBaseURL(BASE, "<html><body>$chapter</body></html>", "text/html", "UTF-8", null)
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun aServedPictureDrawsAndAFailedOneDoesNot() {
        assertEquals("2,0", awaitWidths())
    }

    @Test
    fun theFetchIsAskedForThePictureAndItsSource() {
        awaitWidths()
        assertEquals(NovelImage("https://site.example/ok.png", "p"), fetched.first { it.url.endsWith("ok.png") })
    }

    /** Both pictures' drawn widths once each has loaded or failed. */
    private fun awaitWidths(): String {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_S)
        while (System.nanoTime() < deadline) {
            val result = evaluate(
                "(function(){var a=document.getElementById('a'),b=document.getElementById('b');" +
                    "return a&&b&&a.complete&&b.complete?a.naturalWidth+','+b.naturalWidth:'';})()",
            )
            if (result.isNotEmpty()) return result
            Thread.sleep(50)
        }
        error("The pictures never settled")
    }

    private fun evaluate(script: String): String {
        val latch = CountDownLatch(1)
        var value = ""
        scenario.onActivity { activity ->
            activity.webView.evaluateJavascript(script) {
                value = it.trim('"')
                latch.countDown()
            }
        }
        latch.await(TIMEOUT_S, TimeUnit.SECONDS)
        return value
    }
}
