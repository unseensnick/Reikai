package reikai.presentation.reader.web

import android.webkit.JavascriptInterface
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

class NovelWebBridgeTest {

    private val calls = mutableListOf<String>()

    /** A bridge over a page that has not reported ready: the report gate holds everything it gets. */
    private val bridge = NovelWebBridge(
        fromDocument = { _, _ -> },
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
        onReady = {},
    )

    /** What the document reports about itself, which means nothing until the page is ready. */
    enum class DocumentReport(val send: NovelWebBridge.() -> Unit) {
        VISIBLE_CHAPTER({ onVisibleChapter("token", "1") }),
        PROGRESS({ onProgress("token", "1", 0.5) }),
        PROGRESS_SETTLED({ onProgressSettled("token", "1", 0.5) }),
        TOP_LINE({ onTopLine("token", "1", 3) }),
        CHAPTER_FITS({ onChapterFits("token", "1", true) }),
        CHAPTER_END_SEEN({ onChapterEndSeen("token", "1") }),
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
