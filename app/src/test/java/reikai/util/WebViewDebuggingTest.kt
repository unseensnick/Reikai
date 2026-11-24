package reikai.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

class WebViewDebuggingTest {

    // A debug build keeps its inspector with developer tools off; a release build opens it only when they are on.
    @ParameterizedTest
    @CsvSource("false, false, false", "true, false, true", "false, true, true")
    fun `the inspector is open with developer tools on or on a debug build`(
        devTools: Boolean,
        debugBuild: Boolean,
        open: Boolean,
    ) {
        webContentsDebugging(devTools = devTools, debugBuild = debugBuild) shouldBe open
    }
}
