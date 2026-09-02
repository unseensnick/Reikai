package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.interceptor.TurnstileSolver.Solve.Phase

/**
 * The decisions one Turnstile solve makes: when to press, when to accept, and when to give up.
 *
 * Split out of [TurnstileSolver.attach] because every timing bug this feature has produced lived
 * here and none of it needs a WebView. The clock, the scheduler and the three effects arrive as
 * lambdas, so a test drives time by hand; what stays in `attach` is the handful of lines that do
 * touch Android. Everything here runs on one thread, the main one, which is why nothing but [phase]
 * is guarded.
 */
internal class SolveMachine(
    private val watching: Boolean,
    private val now: () -> Long,
    private val schedule: (delay: Long, action: () -> Unit) -> Unit,
    private val press: () -> Unit,
    private val askClearance: () -> Boolean,
    private val giveUp: () -> Boolean,
    private val log: (String) -> Unit,
) {

    // Written on the main thread, read from the OkHttp thread after a wait that may have ended on a
    // timeout rather than a countDown, so there is no happens-before to lean on.
    @Volatile
    var phase: Phase = Phase.Watching
        private set

    private var lastPress = 0L
    private var hasPressed = false
    private var clearReadings = 0
    private var deadline = 0L

    /**
     * Starts the only deadline this solve has, before anything else can happen. Armed here rather
     * than on the first press because a solve that never presses is the case with nothing else to
     * bound it: arming suppresses the caller's own aborts.
     */
    fun arm() {
        deadline = now() + SOLVE_BUDGET_MS
        schedule(SOLVE_BUDGET_MS, ::checkDeadline)
    }

    /**
     * One Cloudflare challenge event, from the isolated-world probe where there is one and the
     * caller's page-world bridge where there is not.
     *
     * `fail` is deliberately absent: the caller acts on it until the challenge turns interactive and
     * then stops, because Cloudflare reissues after a failed round often enough that pressing
     * through pays.
     */
    fun onEvent(event: String) {
        if (phase == Phase.Watching && event == INTERACTIVE_BEGIN) {
            phase = Phase.Interactive
            log("interactive began")
            // With no probe there are no ticks to press on, so the transition is the trigger.
            if (!watching) pressWhenDue(hasToken = false)
        }

        // Cloudflare saying it accepted beats inferring it from the markup, and on a site that
        // embeds Turnstile on its own pages it is the only signal that can ever arrive: the probe
        // counts the response-token input as a challenge, so such a page never reads clear and the
        // markup path never fires however many times it really succeeded.
        if (phase < Phase.Verified && event == COMPLETE) {
            phase = Phase.Verified
            log("cloudflare reports the challenge complete")
            pollForClearance(ACCEPT_POLL_ATTEMPTS)
        } else if (phase == Phase.Verified) {
            // A probe tick while the clearance is still landing, and the only thing left asking once
            // the poll above runs out. Only the acceptance is logged, since with a probe this runs
            // twice a second until the caller takes it.
            accept()
        }
    }

    /**
     * One probe reading: the events it collected since the last one, whether the page still looks
     * like a challenge, and whether the widget already carries a response token.
     */
    fun onProbe(events: List<String>, challenged: Boolean, hasToken: Boolean) {
        if (phase == Phase.Accepted) return
        events.forEach(::onEvent)
        // Cloudflare is done either way, and pressing again restarts the verification it has just
        // finished, so this never falls through to the press below.
        if (phase == Phase.Verified || phase == Phase.Accepted) return

        if (challenged) {
            clearReadings = 0
            pressWhenDue(hasToken)
            return
        }

        // One clear reading is not the end of it: the markup goes while the next round is issued
        // (measured by Byparr, and by Solverr's own confirm delay).
        if (++clearReadings == 1) log("page reads clear, confirming")
        if (clearReadings >= 2) {
            // Verified without Cloudflare having said so: the page really did get past the
            // interstitial, which is what the caller needs before it may read anything into a
            // clearance arriving late.
            phase = Phase.Verified
            val accepted = askClearance()
            if (accepted) phase = Phase.Accepted
            log("challenge cleared, accepted $accepted")
        }
    }

    private fun pressWhenDue(hasToken: Boolean) {
        // Pressing belongs to exactly one phase. The widget is present from the first paint, well
        // before there is a checkbox behind it, and pressing then only starts the cooldown that
        // delays the press that counts; pressing after Cloudflare has said `complete` restarts the
        // verification it just finished. Both are unreachable from here rather than guarded.
        if (phase != Phase.Interactive) return
        val at = now()
        // A widget that already carries a token is mid-verification, and pressing restarts it.
        // Ported from Byparr and Solverr, which agree on the rule; an interstitial never fills the
        // token in the main frame, so on the pages this feature sees it is the cooldown beside it
        // that does the work. The cooldown is measured only against a press that happened, since a
        // zero [lastPress] would otherwise silence the first one whenever the clock reads low.
        if (hasToken || (hasPressed && at - lastPress < PRESS_COOLDOWN_MS)) return

        press()
        lastPress = at
        // The first press earns a fresh budget, so a challenge that turns interactive late gets the
        // same window to verify as one that turns early. Only the first: the cooldown is shorter
        // than the budget, so extending on every press lets a solve that keeps pressing outrun its
        // own give-up forever, which is the one case the deadline exists for. Watched that happen
        // for 23 seconds and six presses with no give-up line.
        if (!hasPressed) {
            hasPressed = true
            deadline = at + SOLVE_BUDGET_MS
        }
    }

    /**
     * Asks the caller again for the clearance a verified solve is still waiting on. A probe retries
     * on its own ticks too, but only while the page it watches is still there: the navigation that
     * carries the clearance is also the one that ends the probe's reports.
     */
    private fun pollForClearance(attemptsLeft: Int) {
        if (phase != Phase.Verified || attemptsLeft <= 0) return
        if (!accept()) schedule(ACCEPT_POLL_MS) { pollForClearance(attemptsLeft - 1) }
    }

    private fun accept(): Boolean {
        if (!askClearance()) return false
        phase = Phase.Accepted
        log("challenge complete, accepted")
        return true
    }

    /**
     * Reports only a give-up that released something: a request the caller already served from the
     * jar leaves this to fire into nothing, and a give-up line beside a success sends the next
     * reader hunting.
     */
    private fun checkDeadline() {
        val left = deadline - now()
        when {
            left > 0 -> schedule(left, ::checkDeadline)
            phase != Phase.Accepted && giveUp() -> log("gave up, $SOLVE_BUDGET_MS ms with no progress")
        }
    }
}

/** Byparr and Solverr both measured that pressing again mid-verification restarts it. */
private const val PRESS_COOLDOWN_MS = 4000L

/**
 * How long a solve gets before the request is failed, measured from arming and pushed out once by
 * its first press. Key-path solves land in 2.2 to 3.9 seconds and the caller's own wait is 30, so
 * this fails a hopeless solve sooner while leaving room for a challenge Cloudflare reissues.
 */
internal const val SOLVE_BUDGET_MS = 20_000L

/**
 * How often, and how many times, a verified solve asks for the clearance. It lands with the
 * navigation after Cloudflare says complete, not with the event, so the attempt made on the event is
 * always too early and something has to ask again. Five seconds covers a navigation; the caller's
 * wait covers anything longer.
 */
private const val ACCEPT_POLL_MS = 250L
private const val ACCEPT_POLL_ATTEMPTS = 20

/** The events Cloudflare posts that this decides anything from. */
private const val INTERACTIVE_BEGIN = "interactiveBegin"
private const val COMPLETE = "complete"
