package reikai.presentation.reader

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Turns the device's animations off for a case, as Android UI tests expect, and puts back what it had. A
 * loading picture's box pulses by redrawing its line every frame (NovelImageGetter), so the main thread never
 * goes idle for `waitForIdleSync` while a held picture waits, and a case stalls until the picture times out.
 */
class AnimationsOffRule : TestRule {

    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val before = shell("settings get global $SCALE").trim()
            shell("settings put global $SCALE 0")
            try {
                base.evaluate()
            } finally {
                shell(
                    if (before.toFloatOrNull() ==
                        null
                    ) {
                        "settings delete global $SCALE"
                    } else {
                        "settings put global $SCALE $before"
                    },
                )
            }
        }
    }

    /** Read to its end, which is also what waits for the command to finish. */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { it.readBytes().decodeToString() }

    private companion object {
        const val SCALE = "animator_duration_scale"
    }
}
