package reikai.presentation.browse

import android.os.Trace
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * The one gesture rule every browse result follows, catalogue, global search, feed and follows alike.
 * Each call records what it did, so a test reads the whole outcome of one gesture as a single list.
 */
class EntryGesturesTest {

    private val calls = mutableListOf<String>()
    private val haptic = object : HapticFeedback {
        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            calls += "buzz"
        }
    }

    // The runtime traces each composition through android.os.Trace, which the unit-test stubs throw on.
    @BeforeEach
    fun stubTrace() {
        mockkStatic(Trace::class)
        every { Trace.beginSection(any()) } just Runs
        every { Trace.endSection() } just Runs
    }

    @AfterEach
    fun unstubTrace() = unmockkStatic(Trace::class)

    @Test
    fun `a tap while choosing chooses the entry`() = runTest {
        gestures(choosing = true).onClick("row")

        calls shouldBe listOf("choose row")
    }

    @Test
    fun `a long press while choosing previews the entry without a buzz`() = runTest {
        gestures(choosing = true).onLongClick("row")

        calls shouldBe listOf("open row")
    }

    @Test
    fun `a tap while browsing opens the entry`() = runTest {
        gestures(choosing = false).onClick("row")

        calls shouldBe listOf("open row")
    }

    @Test
    fun `a long press while browsing adds the entry and buzzes`() = runTest {
        gestures(choosing = false).onLongClick("row")

        calls shouldBe listOf("add row", "buzz")
    }

    @Test
    fun `a cell's own long-press buzz is silenced while choosing`() = runTest {
        cellBuzzes(choosing = true) shouldBe emptyList()
    }

    @Test
    fun `a cell's own long-press buzz fires while browsing`() = runTest {
        cellBuzzes(choosing = false) shouldBe listOf("buzz")
    }

    @Test
    fun `the gestures a cell holds survive a recomposition`() = runTest {
        val seen = recompose {}

        seen[1] shouldBeSameInstanceAs seen[0]
    }

    @Test
    fun `gestures held from an earlier composition act on the latest inputs`() = runTest {
        val seen = recompose { label -> calls += label }

        seen[0].onClick("row")

        calls shouldBe listOf("second row")
    }

    private fun TestScope.gestures(choosing: Boolean): EntryGestures<String> =
        compose {
            rememberEntryGestures(
                choose = { row: String -> calls += "choose $row" }.takeIf { choosing },
                open = { row -> calls += "open $row" },
                add = { row -> calls += "add $row" },
            )
        }.single()

    /** What a cell's own combinedClickable buzz does under the gestures' haptics. */
    private fun TestScope.cellBuzzes(choosing: Boolean): List<String> {
        val gestures = gestures(choosing)
        compose {
            EntryCellHaptics(gestures) {
                LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)
            }
            gestures
        }
        return calls
    }

    /** Composes once under the label "first", then recomposes under "second"; returns each pass's gestures. */
    private fun TestScope.recompose(onOpen: (String) -> Unit): List<EntryGestures<String>> {
        var label by mutableStateOf("first")
        val seen = compose {
            val current = label
            rememberEntryGestures(choose = null, open = { row -> onOpen("$current $row") }, add = {})
        }
        label = "second"
        Snapshot.sendApplyNotifications()
        runCurrent()
        clock.sendFrame(0L)
        runCurrent()
        return seen
    }

    private val clock = BroadcastFrameClock()

    private fun TestScope.compose(
        content: @Composable () -> EntryGestures<String>,
    ): MutableList<EntryGestures<String>> {
        val recomposer = Recomposer(backgroundScope.coroutineContext)
        backgroundScope.launch(clock) { recomposer.runRecomposeAndApplyChanges() }
        runCurrent()
        val seen = mutableListOf<EntryGestures<String>>()
        Composition(NoopApplier(), recomposer).setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptic) {
                seen += content()
            }
        }
        return seen
    }

    private class NoopApplier : AbstractApplier<Unit>(Unit) {
        override fun insertTopDown(index: Int, instance: Unit) = Unit
        override fun insertBottomUp(index: Int, instance: Unit) = Unit
        override fun remove(index: Int, count: Int) = Unit
        override fun move(from: Int, to: Int, count: Int) = Unit
        override fun onClear() = Unit
    }
}
