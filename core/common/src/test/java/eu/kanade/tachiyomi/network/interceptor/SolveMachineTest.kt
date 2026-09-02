package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.interceptor.TurnstileSolver.Solve.Phase
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The solve's timing decisions, which is where every bug this feature has produced has lived: a
 * press scheduled on a view that never drained its queue, a phase that could fall back, an
 * acceptance asked for twice, and a deadline a pressing solve could outrun forever. None of that
 * needs a WebView, so these drive the machine against a clock the test owns.
 */
class SolveMachineTest {

    /** A clock and a task queue the test advances by hand, plus counters for the three effects. */
    private class Fake {
        var nowMs = 1_000L
        var presses = 0
        var clearanceAsked = 0
        var clearanceLands = false
        var gaveUp = 0
        val lines = mutableListOf<String>()
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()

        fun schedule(delay: Long, action: () -> Unit) {
            pending += (nowMs + delay) to action
        }

        /** Runs everything due within [by], letting tasks that reschedule themselves do so. */
        fun advance(by: Long) {
            val target = nowMs + by
            while (true) {
                val next = pending.filter { it.first <= target }.minByOrNull { it.first } ?: break
                pending.remove(next)
                nowMs = next.first
                next.second()
            }
            nowMs = target
        }
    }

    private fun machine(fake: Fake, watching: Boolean = true) = SolveMachine(
        watching = watching,
        now = { fake.nowMs },
        schedule = fake::schedule,
        press = { fake.presses++ },
        askClearance = {
            fake.clearanceAsked++
            fake.clearanceLands
        },
        giveUp = {
            fake.gaveUp++
            true
        },
        log = fake.lines::add,
    )

    private fun interactive(m: SolveMachine) = m.onProbe(
        listOf("interactiveBegin"),
        challenged = true,
        hasToken = false,
    )

    private fun tick(m: SolveMachine, challenged: Boolean = true, hasToken: Boolean = false) =
        m.onProbe(emptyList(), challenged = challenged, hasToken = hasToken)

    @Test
    fun `a solve that never presses gives up one budget after arming`() {
        val fake = Fake()
        val m = machine(fake)

        m.arm()
        fake.advance(SOLVE_BUDGET_MS)

        fake.gaveUp shouldBe 1
    }

    @Test
    fun `a solve that keeps pressing still gives up one budget after its first press`() {
        // The bug this pins: extending the deadline on every press let a solve outrun its own
        // give-up forever, because the press cooldown is shorter than the budget. Watched on device
        // for 23 seconds and six presses with no give-up line.
        val fake = Fake()
        val m = machine(fake)
        m.arm()
        interactive(m)

        fake.advance(4_000)
        tick(m)
        fake.advance(4_000)
        tick(m)
        fake.advance(4_000)
        tick(m)
        fake.advance(4_000)
        tick(m)
        fake.advance(4_000)
        tick(m)

        fake.gaveUp shouldBe 1
    }

    @Test
    fun `five presses land across one budget`() {
        val fake = Fake()
        val m = machine(fake)
        m.arm()
        interactive(m)

        fake.advance(4_000)
        tick(m)
        fake.advance(4_000)
        tick(m)
        fake.advance(4_000)
        tick(m)
        fake.advance(4_000)
        tick(m)

        fake.presses shouldBe 5
    }

    @Test
    fun `an accepted solve never gives up`() {
        val fake = Fake()
        fake.clearanceLands = true
        val m = machine(fake)

        m.arm()
        m.onEvent("complete")
        fake.advance(SOLVE_BUDGET_MS * 2)

        fake.gaveUp shouldBe 0
    }

    @Test
    fun `nothing is pressed before the challenge turns interactive`() {
        val fake = Fake()
        val m = machine(fake)

        m.arm()
        tick(m)

        fake.presses shouldBe 0
    }

    @Test
    fun `a press inside the cooldown is skipped`() {
        val fake = Fake()
        val m = machine(fake)
        m.arm()
        interactive(m)

        fake.advance(3_999)
        tick(m)

        fake.presses shouldBe 1
    }

    @Test
    fun `a widget that already carries a token is not pressed`() {
        val fake = Fake()
        val m = machine(fake)

        m.arm()
        m.onProbe(listOf("interactiveBegin"), challenged = true, hasToken = true)

        fake.presses shouldBe 0
    }

    @Test
    fun `an accepted solve stops reacting to a reissued complete`() {
        // Cloudflare reissues, so a second complete can arrive after the caller has taken the solve.
        // Asserting the phase alone would pass either way, since re-accepting lands back on
        // Accepted; what says the solve ignored the event is that it did not ask again.
        val fake = Fake()
        fake.clearanceLands = true
        val m = machine(fake)
        m.arm()
        m.onEvent("complete")

        m.onEvent("complete")

        fake.clearanceAsked shouldBe 1
    }

    @Test
    fun `complete keeps asking for the clearance while it is still landing`() {
        // The clearance arrives with the navigation after the event, so the attempt made on the
        // event itself is always too early.
        val fake = Fake()
        val m = machine(fake)
        m.arm()

        m.onEvent("complete")
        fake.advance(500)

        fake.clearanceAsked shouldBe 3
    }

    @Test
    fun `the clearance poll gives up after its attempts run out`() {
        val fake = Fake()
        val m = machine(fake)
        m.arm()

        m.onEvent("complete")
        fake.advance(10_000)

        fake.clearanceAsked shouldBe 20
    }

    @Test
    fun `two clear readings verify a solve Cloudflare never reported`() {
        val fake = Fake()
        val m = machine(fake)
        m.arm()
        interactive(m)

        tick(m, challenged = false)
        tick(m, challenged = false)

        m.phase shouldBe Phase.Verified
    }

    @Test
    fun `one clear reading is not enough to verify`() {
        val fake = Fake()
        val m = machine(fake)
        m.arm()
        interactive(m)

        tick(m, challenged = false)

        m.phase shouldBe Phase.Interactive
    }

    @Test
    fun `with no probe the interactive event presses on its own`() {
        val fake = Fake()
        val m = machine(fake, watching = false)

        m.arm()
        m.onEvent("interactiveBegin")

        fake.presses shouldBe 1
    }
}
