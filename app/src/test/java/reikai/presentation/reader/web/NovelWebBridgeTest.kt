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

    /** A bridge over a page that has reported ready, so each report reaches its handler, its token first. */
    private val readyBridge = bridge(fromDocument = { token, call ->
        calls += "gate $token"
        call()
    })

    // Each handler records what it was handed, so a swapped or dropped argument shows.
    private fun bridge(fromDocument: (String, () -> Unit) -> Unit) = NovelWebBridge(
        fromDocument = fromDocument,
        fromReader = { token, call ->
            calls += "gate $token"
            call()
        },
        onVisibleChapter = { calls += "visible $it" },
        onProgress = { id, fraction -> calls += "progress $id $fraction" },
        onProgressSettled = { id, fraction -> calls += "settled $id $fraction" },
        onTopLine = { id, line -> calls += "line $id $line" },
        onRetryBoundary = { calls += "retry $it" },
        onTap = { x, y -> calls += "tap $x $y" },
        onStepChapter = { calls += "step $it" },
        onChapterFits = { id, fits -> calls += "fits $id $fits" },
        onChapterEndSeen = { calls += "end $it" },
        onPagedMoved = { calls += "paged" },
        onPagedPicturesLanded = { calls += "pictures $it" },
        onPictureOpened = { src, chapterId -> calls += "picture $src $chapterId" },
        onReady = {},
    )

    /** What the document reports about itself, which means nothing until the page is ready. */
    enum class DocumentReport(val send: NovelWebBridge.() -> Unit, val reaches: String) {
        VISIBLE_CHAPTER({ onVisibleChapter("token", "7") }, "visible 7"),
        PROGRESS({ onProgress("token", "7", 0.5) }, "progress 7 0.5"),
        PROGRESS_SETTLED({ onProgressSettled("token", "7", 0.5) }, "settled 7 0.5"),
        TOP_LINE({ onTopLine("token", "7", 3) }, "line 7 3"),
        CHAPTER_FITS({ onChapterFits("token", "7", true) }, "fits 7 true"),
        CHAPTER_END_SEEN({ onChapterEndSeen("token", "7") }, "end 7"),
        PAGED_MOVED({ onPagedMoved("token") }, "paged"),
        PAGED_PICTURES_LANDED({ onPagedPicturesLanded("token", "7") }, "pictures 7"),
    }

    /** What the reader's own finger does, which a chapter script holding up the parse must not block. */
    enum class ReaderCall(val send: NovelWebBridge.() -> Unit, val reaches: String) {
        TAP({ onTap("token", 0.25, 0.75) }, "tap 0.25 0.75"),
        STEP_CHAPTER({ onStepChapter("token", true) }, "step true"),
        RETRY_BOUNDARY({ onRetryBoundary("token", false) }, "retry false"),

        // With the chapter the picture is in, which names the saved file when the window spans chapters.
        PICTURE_OPENED({
            onPictureOpened("token", "data:image/png;base64,AA", "7")
        }, "picture data:image/png;base64,AA 7"),
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
        calls shouldBe listOf("gate token", report.reaches)
    }

    @ParameterizedTest
    @EnumSource(ReaderCall::class)
    fun `a reader call reaches the host before the page reports ready`(call: ReaderCall) {
        bridge.(call.send)()
        calls shouldBe listOf("gate token", call.reaches)
    }

    /** The page sends -1 while no chapter text is at the top; carried on, a rebuild would land on line 0. */
    @Test
    fun `no top line reaches the host as none`() {
        readyBridge.onTopLine("token", "7", -1)
        calls shouldBe listOf("gate token", "line 7 null")
    }

    /** So a method added to the bridge cannot go unrouted by the two cases above; onReady is the gate. */
    @Test
    fun `every bridge method is checked`() {
        val methods = NovelWebBridge::class.java.methods
        methods.count { it.isAnnotationPresent(JavascriptInterface::class.java) } shouldBe
            DocumentReport.entries.size + ReaderCall.entries.size + 1
    }
}
