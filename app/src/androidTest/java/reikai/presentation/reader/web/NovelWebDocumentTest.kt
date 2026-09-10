package reikai.presentation.reader.web

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import reikai.presentation.novel.reader.NovelReaderSettings
import reikai.presentation.novel.reader.ReaderMargins
import reikai.presentation.reader.WebViewHostActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * That the WebView rendering mode's document is one a browser can actually run. A token left
 * unsubstituted is a JavaScript syntax error, which renders a blank page and reports nothing, so it
 * looks the same as a chapter that simply failed to load. This proves the engine started instead.
 */
@RunWith(AndroidJUnit4::class)
class NovelWebDocumentTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private lateinit var webView: WebView

    private companion object {
        const val CHAPTER_ID = 4242L
        const val TIMEOUT_S = 10L
    }

    private val settings = NovelReaderSettings(
        fontSize = 18,
        lineHeight = 1.6f,
        textAlign = "left",
        margins = ReaderMargins(top = 24, bottom = 24, left = 16, right = 16),
        paragraphIndent = 1f,
        paragraphSpacing = 0.6f,
        fontFamily = "",
        followSystemTheme = false,
        backgroundColor = "#101010",
        textColor = "#eeeeee",
        keepScreenOn = false,
        orientation = 0,
        resolvedOrientation = 0,
        ttsEnabled = false,
        ttsRate = 1f,
        ttsPitch = 1f,
        ttsAutoPageAdvance = false,
        ttsScrollToTop = false,
        bionicReading = false,
        removeExtraSpacing = false,
        tapToScroll = true,
        swipeGestures = true,
        showProgressPercentage = false,
        autoScroll = false,
        autoScrollSpeed = 1f,
        useVolumeButtons = false,
        volumeButtonsInverted = false,
        volumeButtonsFraction = 0.75f,
        railHeightPercent = 60,
        railOnLeft = false,
    )

    private fun document(html: String = "<p>lorem ipsum</p>".repeat(200)): String =
        NovelWebDocument.build(
            context = instrumentation.targetContext,
            chapterId = CHAPTER_ID,
            chapterHtml = html,
            initialFraction = 0f,
            settings = settings,
            statusBarHeightPx = 0,
            customFontUrl = null,
        )

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
        scenario.onActivity {
            webView = it.webView
            webView.settings.javaScriptEnabled = true
        }
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun everyBuildTokenIsSubstituted() {
        val leftover = Regex("__[A-Z_]+__").findAll(document()).map { it.value }.toSet()
        assertTrue("the document still carries build tokens: $leftover", leftover.isEmpty())
    }

    @Test
    fun theEngineStartsAndReportsReady() {
        val ready = CountDownLatch(1)
        val bridge = object {
            @android.webkit.JavascriptInterface
            fun onReady() = ready.countDown()

            @android.webkit.JavascriptInterface
            fun onVisibleChapter(chapterId: String) = Unit

            @android.webkit.JavascriptInterface
            fun onProgress(chapterId: String, fraction: Double) = Unit

            @android.webkit.JavascriptInterface
            fun onProgressSettled(chapterId: String, fraction: Double) = Unit

            @android.webkit.JavascriptInterface
            fun onReachedEnd(chapterId: String) = Unit

            @android.webkit.JavascriptInterface
            fun onReachedStart(chapterId: String) = Unit

            @android.webkit.JavascriptInterface
            fun onToggleMenu() = Unit

            @android.webkit.JavascriptInterface
            fun onStepChapter(forward: Boolean) = Unit
        }
        val finished = CountDownLatch(1)
        instrumentation.runOnMainSync {
            webView.addJavascriptInterface(bridge, NovelWebBridge.NAME)
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String?) = finished.countDown()
            }
            webView.loadDataWithBaseURL(null, document(), "text/html", "UTF-8", null)
        }
        assertTrue("the document never finished loading", finished.await(TIMEOUT_S, TimeUnit.SECONDS))
        assertTrue(
            "the page never reported ready, so its script did not run to the end",
            ready.await(TIMEOUT_S, TimeUnit.SECONDS),
        )
        assertEquals("the chapter is not in the document", "1", eval("document.querySelectorAll('.rk-chapter').length"))
        assertEquals(
            "the chapter is not the one asked for",
            CHAPTER_ID.toString(),
            eval(
                "document.querySelector('.rk-chapter').getAttribute('data-rk-chapter-id')",
            ),
        )
    }

    private fun eval(js: String): String {
        val done = CountDownLatch(1)
        var result = ""
        instrumentation.runOnMainSync {
            webView.evaluateJavascript(js) { value ->
                result = value.orEmpty().trim().removeSurrounding("\"")
                done.countDown()
            }
        }
        assertTrue("javascript never returned: $js", done.await(TIMEOUT_S, TimeUnit.SECONDS))
        return result
    }
}
