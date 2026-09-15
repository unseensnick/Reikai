package reikai.presentation.reader

import android.view.View
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import reikai.domain.novel.NovelPreferences
import reikai.novel.content.NovelContentConfig
import reikai.novel.content.NovelContentPipeline
import reikai.novel.content.RenderTarget
import reikai.presentation.reader.text.ChapterTextBlock
import reikai.presentation.reader.text.NovelTextRenderer
import tachiyomi.core.common.preference.InMemoryPreferenceStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * That both novel renderers show a chapter as the same lines. Read-aloud counts a paragraph as a
 * non-blank line of what the renderer shows, so a construct one renderer lays out as two lines and the
 * other as one would read differently depending on the mode. Each fixture goes through the content
 * pipeline for its renderer's own target, as the loader sends it, and then through the real renderer.
 */
@RunWith(Parameterized::class)
class RenderedLinesParityTest(private val fixture: Fixture) {

    data class Fixture(val name: String, val html: String) {
        override fun toString() = name
    }

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var scenario: ActivityScenario<WebViewHostActivity>
    private val scope = MainScope()
    private val preferences = NovelPreferences(InMemoryPreferenceStore())
    private val pipeline = NovelContentPipeline(preferences)

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(WebViewHostActivity::class.java)
    }

    @After
    fun tearDown() {
        scope.cancel()
        if (::scenario.isInitialized) scenario.close()
    }

    @Test
    fun bothRenderersShowTheSameLines() {
        assertEquals(webLines(), nativeLines())
    }

    private fun processed(target: RenderTarget): String = runBlocking {
        val config = NovelContentConfig.from(
            preferences = preferences,
            target = target,
            chapterUrl = CHAPTER_URL,
            chapterName = "Chapter 1",
        )
        pipeline.process(fixture.html, config).text
    }

    private fun nativeLines(): List<String> {
        val html = processed(RenderTarget.TEXT_VIEW)
        lateinit var block: ChapterTextBlock
        lateinit var renderer: NovelTextRenderer
        scenario.onActivity { activity ->
            block = ChapterTextBlock(activity) {
                TextView(activity).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
            }
            renderer = NovelTextRenderer(activity, scope) { _, _ -> }
            activity.setContentView(block.container)
        }
        runBlocking(Dispatchers.Main) {
            renderer.render(
                block = block,
                html = html,
                fontSize = 18,
                paragraphSpacing = 0f,
                paragraphIndent = 0f,
                selectable = false,
                bionic = false,
                contentWidth = COLUMN_PX,
                baseUrl = null,
                onTextSet = {},
            ).join()
        }
        var text = ""
        instrumentation.runOnMainSync { text = block.chunkViews.joinToString("\n") { it.text.toString() } }
        return lines(text)
    }

    private fun webLines(): List<String> {
        val html = processed(RenderTarget.WEB_VIEW)
        val rendered = CountDownLatch(1)
        lateinit var viewport: NovelWebViewport
        scenario.onActivity { activity ->
            viewport = NovelWebViewport(
                context = activity,
                textSelectable = false,
                volumeKeysActive = { false },
                useOriginalFonts = false,
                sourceCssPriority = false,
                onProgressChanged = { _, _ -> },
                onProgressSettled = { _, _ -> },
                onToggleMenu = {},
                onStepChapter = {},
                onVisibleChapter = {},
                onRetryBoundary = {},
                cutoutTopDp = { 0 },
                onChapterFits = { _, _ -> rendered.countDown() },
                onChapterEndSeen = {},
            )
            activity.setContentView(viewport.view)
        }
        try {
            runBlocking(Dispatchers.Main) { viewport.load(chapter(html), readerTestSettings) }
            rendered.await(TIMEOUT_S, TimeUnit.SECONDS)
            val quoted = eval(viewport.view, "document.querySelector('.rk-chapter[data-rk-chapter-id]').innerText")
            return lines(JSONArray("[$quoted]").getString(0))
        } finally {
            instrumentation.runOnMainSync { viewport.destroy() }
        }
    }

    /** A line as read-aloud will count one: whitespace collapsed, an image's placeholder dropped. */
    private fun lines(text: String): List<String> = text.split('\n')
        .map { it.replace(OBJECT_REPLACEMENT, "").replace(whitespace, " ").trim() }
        .filter { it.isNotEmpty() }

    private fun chapter(html: String) = NovelReaderViewModel.LoadedChapter(
        chapterId = 1L,
        title = "Chapter 1",
        url = CHAPTER_URL,
        html = html,
        baseUrl = null,
        progressPercent = 0,
        chapterNumber = 1.0,
        novelId = 1L,
        downloaded = false,
        isLast = false,
    )

    private fun eval(view: View, js: String): String {
        val done = CountDownLatch(1)
        var result = "null"
        instrumentation.runOnMainSync {
            (view as WebView).evaluateJavascript(js) { value ->
                result = value ?: "null"
                done.countDown()
            }
        }
        done.await(TIMEOUT_S, TimeUnit.SECONDS)
        return result
    }

    companion object {
        const val TIMEOUT_S = 10L
        const val COLUMN_PX = 600

        /** No extension, so the pipeline decides the chapter's kind from its content, as most sources get. */
        const val CHAPTER_URL = "/chapter/1"

        /** What a TextView holds in an image span's place. */
        private val OBJECT_REPLACEMENT = Char(0xFFFC).toString()
        private val whitespace = Regex("[\\s\\u00A0]+")

        private const val PNG =
            "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg=="

        private val chapterShaped = "<h4>Chapter Four: The Long Road</h4>" + (1..20).joinToString("") { n ->
            "<p>Paragraph $n began where the last one left off, and the <i>carriage</i> rolled on " +
                "past field $n while nobody in it said a word.</p>"
        }

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun fixtures(): List<Fixture> = listOf(
            Fixture("p", "<p>One.</p><p>Two.</p>"),
            Fixture("br_in_p", "<p>Line a<br>Line b</p>"),
            Fixture("double_br_bare", "First<br><br>Second"),
            Fixture("div", "<div>A</div><div>B</div>"),
            Fixture("div_p", "<div><p>A</p><p>B</p></div>"),
            Fixture("span_in_p", "<p>Hello <span>world</span>.</p>"),
            Fixture(
                "inline",
                "<p><b>Bold</b> and <i>italic</i> and <u>under</u> and x<sup>2</sup> <small>s</small></p>",
            ),
            Fixture("headings", "<h1>Title</h1><p>Body</p><h3>Sub</h3><h4>Four</h4>"),
            Fixture("lists", "<ul><li>One</li><li>Two</li></ul><ol><li>Three</li></ol>"),
            Fixture("blockquote_p", "<blockquote><p>Quote one</p><p>Quote two</p></blockquote>"),
            Fixture("blockquote_bare", "<blockquote>Quoted</blockquote><p>After</p>"),
            Fixture("section_article", "<section><p>S1</p></section><article>A1</article><p>After</p>"),
            Fixture("table", "<table><tr><td>c1</td><td>c2</td></tr><tr><td>c3</td></tr></table>"),
            Fixture("pre", "<pre>line1\nline2</pre>"),
            Fixture("img_in_text", "<p>Before <img src=\"$PNG\"> after</p>"),
            Fixture("img_alone", "<p><img src=\"$PNG\"></p><p>Text</p>"),
            Fixture("img_bare", "<img src=\"$PNG\"><p>Text</p>"),
            Fixture("ruby", "<p>漢<ruby>字<rt>かんじ</rt></ruby>です。</p>"),
            Fixture("cjk", "<p>第一段。</p><p>第二段。</p>"),
            Fixture("whitespace", "<p>  spaced   out  </p>\n\n<p>&nbsp;</p><p>x</p>"),
            Fixture("hr", "<p>a</p><hr><p>b</p>"),
            Fixture("bare_newlines", "Para one.\n\nPara two."),
            // A tag makes the pipeline take this as HTML, so the blank line reaches the renderers as is.
            Fixture("bare_newlines_tagged", "Para <b>one</b>.\n\nPara two."),
            Fixture("nested_inline", "<p><strong><em>x</em></strong> y</p><center>c</center>"),
            Fixture("div_mixed", "<div>Lead text<p>Inner</p>Tail</div>"),
            Fixture("empty_p", "<p></p><p> </p><p>Real</p>"),
            Fixture("br_run", "<p>a</p><br><br><br><p>b</p>"),
            Fixture("figure", "<figure><img src=\"$PNG\"><figcaption>Cap</figcaption></figure><p>t</p>"),
            Fixture("dl", "<dl><dt>Term</dt><dd>Def</dd></dl>"),
            Fixture("link", "<p><a href=\"https://example.com\">link</a> text</p>"),
            Fixture("font_center", "<font color=\"red\">F</font><p>P</p>"),
            Fixture("entities", "<p>&ldquo;Hi,&rdquo; she said&hellip; it&rsquo;s 3.14 &amp; co</p>"),
            Fixture("chapter_shaped", chapterShaped),
        )
    }
}
