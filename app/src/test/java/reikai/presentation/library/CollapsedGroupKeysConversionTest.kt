package reikai.presentation.library

import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/** A collapse key saved under a label in the app's language gains the neutral key of each group that label named. */
@RunWith(ParameterizedRobolectricTestRunner::class)
class CollapsedGroupKeysConversionTest(
    private val stored: String,
    private val added: Set<String>,
) {

    @Test
    fun `a stored label key gains its neutral keys`() {
        val converted = DynamicGroupKeys.withNeutralKeys(setOf(stored), ApplicationProvider.getApplicationContext())

        converted shouldBe setOf(stored) + added
    }

    companion object {
        private fun key(id: String) = normalizeDynamicKey(labelledGroupName(id, ""))

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun cases() = listOf(
            // One label named a publishing status and a tracking status, which shared a key.
            arrayOf("completed", setOf(key("rk:status:completed"), key("rk:track:completed"))),
            arrayOf("Plan to read", setOf(key("rk:track:plantoread"))),
            arrayOf("on hiatus", setOf(key("rk:status:on_hiatus"))),
            arrayOf("unknown", setOf(key(DynamicGroupKeys.UNKNOWN))),
            arrayOf("unknown◘•◘0", setOf(key(DynamicGroupKeys.UNKNOWN_SOURCE))),
            arrayOf("not tracked", setOf(key(DynamicGroupKeys.NOT_TRACKED))),
            arrayOf("ungrouped", setOf(key(DynamicGroupKeys.UNGROUPED))),
            // Already neutral, or a tag the app does not name: nothing to add.
            arrayOf(key(DynamicGroupKeys.UNKNOWN), emptySet<String>()),
            arrayOf("action", emptySet<String>()),
        )
    }
}
