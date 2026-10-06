package reikai.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Extensions and novel plugins that did not load share Mihon's crash-log entry (CrashLogUtil). */
class CrashLogNotLoadedEntryTest {

    @Test
    fun `an entry without a stack trace ends at its reason`() {
        crashLogNotLoadedEntry("Alpha", "1.4.2 (lib 1.5)", "Untrusted", stackTrace = null) shouldBe
            "- Alpha\n  Installed: 1.4.2 (lib 1.5)\n  Not loaded: Untrusted"
    }

    @Test
    fun `a failure's stack trace follows its reason, indented and trimmed`() {
        crashLogNotLoadedEntry("Alpha", "1.4.2 (lib 1.5)", "Failed (boom)", "at a\nat b\n\n") shouldBe
            "- Alpha\n  Installed: 1.4.2 (lib 1.5)\n  Not loaded: Failed (boom)\n  at a\n  at b"
    }
}
