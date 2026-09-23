package reikai.presentation.reader.web

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import org.jsoup.Jsoup
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import reikai.presentation.reader.NovelChapterNavigationClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** Closing the reader frees a WebView thread still waiting on a picture, rather than leaving it to time out. */
@RunWith(AndroidJUnit4::class)
class NovelWebImagesCancelTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob())
    private val images = NovelWebImages()
    private val fetchStarted = CountDownLatch(1)
    private lateinit var webView: WebView
    private lateinit var client: NovelChapterNavigationClient

    private companion object {
        const val BASE = "https://site.example/novel/chapter-1"
        const val TIMEOUT_S = 10L
    }

    @Before
    fun setUp() {
        val context = instrumentation.targetContext
        instrumentation.runOnMainSync { webView = WebView(context) }
        // A host that never answers: only a cancellation ends this fetch.
        client = NovelChapterNavigationClient(context, { BASE }, images, scope) {
            fetchStarted.countDown()
            awaitCancellation()
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        instrumentation.runOnMainSync { webView.destroy() }
    }

    @Test
    fun closingTheReaderReleasesAPictureStillLoading() {
        val address = Jsoup.parseBodyFragment(images.rewrite("""<img src="/slow.png">""", BASE, "p"))
            .selectFirst("img")!!.attr("src")
        val answered = CountDownLatch(1)
        thread {
            client.shouldInterceptRequest(webView, request(address))
            answered.countDown()
        }
        // Refused before any fetch, the intercept answers at once and the release below tests nothing.
        val fetching = fetchStarted.await(TIMEOUT_S, TimeUnit.SECONDS)

        scope.cancel()

        assertTrue(fetching && answered.await(TIMEOUT_S, TimeUnit.SECONDS))
    }

    private fun request(url: String) = object : WebResourceRequest {
        override fun getUrl(): Uri = Uri.parse(url)
        override fun isForMainFrame() = false
        override fun isRedirect() = false
        override fun hasGesture() = false
        override fun getMethod() = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }
}
