package reikai.presentation.reader.web

import android.webkit.JavascriptInterface
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class NovelWebBridgeTest {

    private val calls = mutableListOf<String>()

    /** A bridge over a page that has not reported ready: the report gate holds everything it gets. */
    private val bridge = bridge(fromDocument = { _, _ -> })

    /** A bridge over a page that has reported ready, so each report reaches its handler. */
    private val readyBridge = bridge(fromDocument = { _, call -> call() })

    private fun bridge(fromDocument: (String, () -> Unit) -> Unit) = NovelWebBridge(
        fromDocument = fromDocument,
        fromReader = { _, call -> call() },
        onVisibleChapter = { calls += "visible" },
        onProgress = { _, _ -> calls += "progress" },
        onProgressSettled = { _, _ -> calls += "settled" },
        onTopLine = { _, _ -> calls += "line" },
        onRetryBoundary = { calls += "retry" },
        onTap = { _, _ -> calls += "tap" },
        onStepChapter = { calls += "step" },
        onChapterFits = { _, _ -> calls += "fits" },
        onChapterEndSeen = { calls += "end" },
        onPagedMoved = { calls += "paged" },
        onPagedPicturesLanded = { calls += "pictures" },
        onReady = {},
    )

    /** What the document reports about itself, which means nothing until the page is ready. */
    enum class DocumentReport(val send: NovelWebBridge.() -> Unit, val reaches: String) {
        VISIBLE_CHAPTER({ onVisibleChapter("token", "1") }, "visible"),
        PROGRESS({ onProgress("token", "1", 0.5) }, "progress"),
        PROGRESS_SETTLED({ onProgressSettled("token", "1", 0.5) }, "settled"),
        TOP_LINE({ onTopLine("token", "1", 3) }, "line"),
        CHAPTER_FITS({ onChapterFits("token", "1", true) }, "fits"),
        CHAPTER_END_SEEN({ onChapterEndSeen("token", "1") }, "end"),
        PAGED_MOVED({ onPagedMoved("token") }, "paged"),
        PAGED_PICTURES_LANDED({ onPagedPicturesLanded("token", "1") }, "pictures"),
    }

    /** What the reader's own finger does, which a chapter script holding up the parse must not block. */
    enum class ReaderCall(val send: NovelWebBridge.() -> Unit, val reaches: String) {
        TAP({ onTap("token", 0.5, 0.5) }, "tap"),
        STEP_CHAPTER({ onStepChapter("token", true) }, "step"),
        RETRY_BOUNDARY({ onRetryBoundary("token", true) }, "retry"),
    }

    @ParameterizedTest
    @EnumSource(DocumentReport::class)
    fun `a document report waits for the page to report ready`(report: DocumentReport) {
        bridge.(report.send)()
        calls shouldBe emptyList()
    }

    @ParameterizedTest
    @EnumSource(DocumentReport::class)
    fun `a document report reaches its own handler once the page is ready`(report: DocumentReport) {
        readyBridge.(report.send)()
        calls shouldBe listOf(report.reaches)
    }

    @ParameterizedTest
    @EnumSource(ReaderCall::class)
    fun `a reader call reaches the host before the page reports ready`(call: ReaderCall) {
        bridge.(call.send)()
        calls shouldBe listOf(call.reaches)
    }

    /** So a method added to the bridge cannot go unrouted by the two cases above; onReady is the gate. */
    @Test
    fun `every bridge method is checked`() {
        val methods = NovelWebBridge::class.java.methods
        methods.count { it.isAnnotationPresent(JavascriptInterface::class.java) } shouldBe
            DocumentReport.entries.size + ReaderCall.entries.size + 1
    }
}
