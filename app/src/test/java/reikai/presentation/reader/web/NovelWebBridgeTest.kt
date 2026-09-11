package reikai.presentation.reader.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelWebBridgeTest {

    private val calls = mutableListOf<String>()

    /** A bridge over a page that has not reported ready: the report gate holds everything it gets. */
    private val bridge = NovelWebBridge(
        fromDocument = { _, _ -> },
        fromReader = { _, call -> call() },
        onVisibleChapter = { calls += "visible" },
        onProgress = { _, _ -> calls += "progress" },
        onProgressSettled = { _, _ -> calls += "settled" },
        onRetryBoundary = { calls += "retry" },
        onToggleMenu = { calls += "menu" },
        onStepChapter = { calls += "step" },
        onChapterFits = { _, _ -> calls += "fits" },
        onChapterEndSeen = { calls += "end" },
        onReady = {},
    )

    @Test
    fun `a tap opens the menu before the page reports ready`() {
        bridge.onToggleMenu("token")
        calls shouldBe listOf("menu")
    }

    @Test
    fun `a swipe steps a chapter before the page reports ready`() {
        bridge.onStepChapter("token", true)
        calls shouldBe listOf("step")
    }

    @Test
    fun `a tap on Retry reaches the host before the page reports ready`() {
        bridge.onRetryBoundary("token", true)
        calls shouldBe listOf("retry")
    }

    @Test
    fun `a progress report waits for the page to report ready`() {
        bridge.onProgress("token", "1", 0.5)
        calls shouldBe emptyList()
    }
}
