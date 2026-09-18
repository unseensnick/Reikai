package reikai.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class WebViewDebuggingTest {

    @Test
    fun `developer tools switched off close the inspector on a release build`() {
        webContentsDebugging(devTools = false, debugBuild = false) shouldBe false
    }

    @Test
    fun `developer tools switched on open the inspector`() {
        webContentsDebugging(devTools = true, debugBuild = false) shouldBe true
    }

    @Test
    fun `a debug build keeps its inspector with developer tools off`() {
        webContentsDebugging(devTools = false, debugBuild = true) shouldBe true
    }
}
