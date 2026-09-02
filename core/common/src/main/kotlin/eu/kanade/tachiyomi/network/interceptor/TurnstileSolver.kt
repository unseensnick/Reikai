package eu.kanade.tachiyomi.network.interceptor

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import eu.kanade.tachiyomi.util.system.ForegroundActivity
import logcat.LogPriority
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import kotlin.random.Random

/**
 * Presses the checkbox an interactive Cloudflare Turnstile challenge waits on.
 *
 * Sends Tab then Space, with a window or without one. A WebView that was never attached takes the
 * keys just as well once it has been laid out by hand and handed the focus callbacks; what used to
 * make that look impossible was `View.postDelayed` silently queueing every event after the first.
 * The page is polled from an isolated world where the WebView has one, and on a WebView too old for
 * that the solve runs on Cloudflare's own events alone rather than declining. What was tried and
 * failed: docs/dev/plans/turnstile-solver.md.
 */
object TurnstileSolver {

    /** Byparr and Solverr both measured that pressing again mid-verification restarts it. */
    private const val PRESS_COOLDOWN_MS = 4000L
    private const val KEY_GAP_MIN_MS = 70L
    private const val KEY_GAP_MAX_MS = 160L

    /**
     * How long a solve gets before the request is failed, measured from arming and pushed out once
     * by its first press. Key-path solves land in 2.2 to 3.9 seconds and the caller's own wait is 30,
     * so this fails a hopeless solve sooner while leaving room for a challenge Cloudflare reissues.
     * It runs from arming because a solve that never presses is the case with nothing else to bound
     * it: arming suppresses the caller's own aborts.
     */
    private const val SOLVE_BUDGET_MS = 20_000L

    /**
     * How often, and how many times, a verified solve asks for the clearance. It lands with the
     * navigation after Cloudflare says complete, not with the event, so the attempt made on the
     * event is always too early and something has to ask again. Five seconds covers a navigation;
     * the caller's wait covers anything longer.
     */
    private const val ACCEPT_POLL_MS = 250L
    private const val ACCEPT_POLL_ATTEMPTS = 20

    /** The events Cloudflare posts that this decides anything from. */
    private const val INTERACTIVE_BEGIN = "interactiveBegin"
    private const val COMPLETE = "complete"

    /**
     * One solve, and everything the caller may know about it. Returned by [attach], `null` there
     * meaning it did not arm.
     *
     * Phases only ever move forward. [Verified] is the one the caller reads: it says the challenge
     * really was passed, either because Cloudflare said so or because the interstitial was watched
     * going, which is what a clearance arriving late may be trusted against. There is deliberately no
     * gave-up phase, because a solve can run out its budget after reaching [Verified] and overwriting
     * it would throw away the only thing the caller needs.
     */
    class Solve internal constructor() {
        enum class Phase { Watching, Interactive, Verified, Accepted }

        // Written on the main thread, read from the OkHttp thread after a wait that may have ended
        // on a timeout rather than a countDown, so there is no happens-before to lean on.
        @Volatile
        var phase: Phase = Phase.Watching
            internal set

        /**
         * Set only when there is no isolated world to watch from, and then the solve's only input.
         * The caller feeds it every challenge event its own bridge receives.
         */
        internal var onEvent: ((String) -> Unit)? = null

        private val main = Handler(Looper.getMainLooper())
        private val timers = mutableListOf<Runnable>()

        @Volatile
        private var running = true

        /**
         * Runs [action] on the main thread after [delay], unless the solve has been cancelled by
         * then. Never `View.postDelayed`: a view that was never attached to a window queues its
         * posts into its own run queue and only drains them on attach, so with no window every key
         * event after the first silently never fired.
         */
        internal fun post(delay: Long, action: () -> Unit) {
            if (!running) return
            val timer = Runnable { if (running) action() }
            synchronized(timers) { timers += timer }
            main.postDelayed(timer, delay)
        }

        /**
         * Feeds one Cloudflare challenge event in. Does nothing while a probe is watching. Hops to
         * the main thread because the caller's bridge delivers this on WebView's JavaBridge thread,
         * and the solve presses keys at the WebView.
         */
        fun report(event: String) {
            if (onEvent != null) post(0) { onEvent?.invoke(event) }
        }

        /**
         * Stops every timer this solve armed. The caller destroys the WebView as soon as its wait
         * ends, and an undelayed destroy sorts ahead of a delayed key event, so without this a press
         * lands on a destroyed WebView and the give-up and clearance timers outlive the request.
         */
        fun cancel() {
            running = false
            synchronized(timers) {
                timers.forEach(main::removeCallbacks)
                timers.clear()
            }
        }
    }

    private const val BRIDGE = "reikaiTurnstileWatch"
    private const val WORLD = "reikai-turnstile"

    /**
     * Debug builds only, and not persisted. Makes the solver take the no-window path even with a
     * window, since the real trigger is a process that starts with no activity ever created, which a
     * scheduled update reaches and a person holding the phone cannot. Set from the Networking
     * settings row of the same name.
     */
    var forceHeadless: Boolean = false

    /**
     * Debug builds only, and not persisted. Makes the solve run as it does on a WebView with no
     * isolated world, which a device new enough to have one cannot otherwise reach. Set from the
     * Networking settings row of the same name.
     */
    var forceNoWatch: Boolean = false

    /**
     * Whether the installed WebView can run a script in a world the page's own scripts cannot read.
     *
     * `addJavaScriptOnEvent` and this feature arrived in androidx.webkit 1.16.0-alpha03, so it is
     * the recency of the installed WebView that decides, not the Android version.
     */
    val hasIsolatedWorld: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)

    /**
     * Whether this solve gets a probe. A poll in the page's own world is what makes Cloudflare
     * reissue the challenge, so without an isolated world there is nothing safe to poll from and the
     * solve runs on Cloudflare's events alone. Read once per solve, never mid-solve: the debug
     * override can flip under a running one, which used to leave it unable to press.
     */
    private val canWatch: Boolean get() = hasIsolatedWorld && !forceNoWatch

    /**
     * Starts working any Turnstile checkbox [webView] shows, calling [onSolved] once the challenge is
     * gone or [onGiveUp], which reports whether a wait was still open, when the solve runs out its
     * budget. Arming suppresses the caller's own aborts, so it owes one. Returns the [Solve] to read
     * the outcome from, or `null` when it did not arm.
     *
     * [origin] must read `scheme://host[:port]`, port only when not the scheme default, and must be
     * the origin the challenge is actually served from rather than the one first requested. A script
     * only reaches later documents, so this runs before `loadUrl`; [detach] runs when the solve ends.
     */
    fun attach(
        webView: WebView,
        host: String,
        origin: String,
        backgroundEnabled: Boolean,
        onGiveUp: () -> Boolean,
        onSolved: () -> Boolean,
    ): Solve? {
        val container = ForegroundActivity.current?.window?.decorView.takeUnless { forceHeadless } as? ViewGroup
        // Solving with no app screen open is still the user's choice, since it means reaching a
        // challenged host while nothing is on screen to show for it. Not arming leaves the caller's
        // own aborts in charge.
        if (container == null && !backgroundEnabled) return null
        if (container != null) attachToWindow(webView, container) else layOutHeadless(webView)
        // Which press path ran is otherwise invisible in a log, and the two fail differently.
        logcat { "Turnstile[$host]: arming with${if (container == null) "out" else ""} a window" }

        val solve = Solve()
        val watching = canWatch
        var lastPress = 0L
        var clearReadings = 0
        var deadline = SystemClock.uptimeMillis() + SOLVE_BUDGET_MS
        var deadlineExtended = false

        val press = {
            val delivered = pressKeys(webView, solve)
            logcat { "Turnstile[$host]: pressing tab+space (delivered $delivered)" }
            // The first press earns a fresh budget, so a challenge that turns interactive late gets
            // the same window to verify as one that turns early. Only the first: the cooldown is
            // shorter than the budget, so extending on every press lets a solve that keeps pressing
            // outrun its own give-up forever, which is the one case the deadline exists for. Watched
            // that happen for 23 seconds and six presses with no give-up line.
            if (!deadlineExtended) {
                deadlineExtended = true
                deadline = SystemClock.uptimeMillis() + SOLVE_BUDGET_MS
            }
        }

        // [probe] is null with no probe to read, which is the one-press case: nothing reports the
        // page again, so there is no second chance and no token to check before taking it.
        val pressWhenDue = press@{ probe: JSONObject? ->
            // Pressing belongs to exactly one phase. The widget is present from the first paint, well
            // before there is a checkbox behind it, and pressing then only starts the cooldown that
            // delays the press that counts; pressing after Cloudflare has said `complete` restarts
            // the verification it just finished. Both are unreachable from here rather than guarded.
            if (solve.phase != Solve.Phase.Interactive) return@press
            val now = SystemClock.uptimeMillis()
            // A widget that already carries a token is mid-verification, and pressing restarts it.
            // Ported from Byparr and Solverr, which agree on the rule; an interstitial never fills
            // the token in the main frame, so on the pages this feature sees it is the cooldown
            // beside it that does the work.
            if (probe?.optString("token")?.isNotEmpty() == true || now - lastPress < PRESS_COOLDOWN_MS) return@press

            lastPress = now
            press()
        }

        // Asks the caller again for the clearance a verified solve is still waiting on. A probe
        // retries on its own ticks too, but only while the page it watches is still there: the
        // navigation that carries the clearance is also the one that ends the probe's reports.
        lateinit var pollForClearance: (Int) -> Unit
        pollForClearance = { attemptsLeft ->
            if (solve.phase == Solve.Phase.Verified && attemptsLeft > 0) {
                if (onSolved()) {
                    solve.phase = Solve.Phase.Accepted
                    logcat { "Turnstile[$host]: challenge complete, accepted" }
                } else {
                    solve.post(ACCEPT_POLL_MS) { pollForClearance(attemptsLeft - 1) }
                }
            }
        }

        // The two events the solve turns on, wherever they came from: the isolated-world probe when
        // there is one, the caller's page-world bridge when there is not. `fail` is deliberately not
        // acted on once the challenge has turned interactive, because Cloudflare reissues after a
        // failed round often enough that pressing through pays; the caller aborts on it up to then.
        val handleEvent = { event: String ->
            if (solve.phase == Solve.Phase.Watching && event == INTERACTIVE_BEGIN) {
                solve.phase = Solve.Phase.Interactive
                logcat { "Turnstile[$host]: interactive began" }
                // With no probe there are no ticks to press on, so the transition is the trigger.
                if (!watching) pressWhenDue(null)
            }

            // Cloudflare saying it accepted beats inferring it from the markup, and on a site that
            // embeds Turnstile on its own pages it is the only signal that can ever arrive: the
            // probe counts the response-token input as a challenge, so such a page never reads clear
            // and the markup path never fires however many times it really succeeded.
            if (solve.phase < Solve.Phase.Verified && event == COMPLETE) {
                solve.phase = Solve.Phase.Verified
                logcat { "Turnstile[$host]: cloudflare reports the challenge complete" }
                pollForClearance(ACCEPT_POLL_ATTEMPTS)
            } else if (solve.phase == Solve.Phase.Verified) {
                // A probe tick while the clearance is still landing, and the only thing left asking
                // once the poll above runs out. Only the acceptance is logged, since with a probe
                // this runs twice a second until the caller takes it.
                if (onSolved()) {
                    solve.phase = Solve.Phase.Accepted
                    logcat { "Turnstile[$host]: challenge complete, accepted" }
                }
            }
        }

        val onWatch = watch@{ json: String ->
            if (solve.phase == Solve.Phase.Accepted) return@watch
            val probe = runCatching { JSONObject(json) }.getOrNull() ?: return@watch

            probe.optString("cf").split('|').forEach(handleEvent)
            if (solve.phase == Solve.Phase.Verified || solve.phase == Solve.Phase.Accepted) {
                // Cloudflare is done either way, and pressing again restarts the verification it has
                // just finished, so this never falls through to the press below.
                return@watch
            }

            if (probe.optBoolean("challenged", true)) {
                clearReadings = 0
                pressWhenDue(probe)
                return@watch
            }

            // One clear reading is not the end of it: the markup goes while the next round is issued
            // (measured by Byparr, and by Solverr's own confirm delay).
            if (++clearReadings == 1) {
                logcat { "Turnstile[$host]: page reads clear, confirming" }
            }
            if (clearReadings >= 2) {
                // Verified without Cloudflare having said so: the page really did get past the
                // interstitial, which is what the caller needs before it may read anything into a
                // clearance arriving late.
                solve.phase = Solve.Phase.Verified
                val accepted = onSolved()
                if (accepted) solve.phase = Solve.Phase.Accepted
                logcat { "Turnstile[$host]: challenge cleared, accepted $accepted" }
            }
        }

        // The only deadline this solve has, armed here rather than on the first press. Report only a
        // give-up that released something: a request the caller already served from the jar leaves
        // this to fire into nothing, and a give-up line beside a success sends the next reader
        // hunting.
        lateinit var watchDeadline: () -> Unit
        watchDeadline = {
            val left = deadline - SystemClock.uptimeMillis()
            when {
                left > 0 -> solve.post(left, watchDeadline)
                solve.phase != Solve.Phase.Accepted && onGiveUp() ->
                    logcat { "Turnstile[$host]: gave up, $SOLVE_BUDGET_MS ms with no progress" }
            }
        }
        solve.post(SOLVE_BUDGET_MS, watchDeadline)

        if (!watching) {
            // No isolated world to poll from, so the solve runs on Cloudflare's own events, which
            // the caller's bridge already receives on every challenged page. Nothing new is put in
            // the page's world, and nothing is lost but the markup fallback: a challenge that never
            // says `complete` is left to the deadline above rather than watched out.
            solve.onEvent = handleEvent
            logcat { "Turnstile[$host]: no isolated world, running on challenge events alone" }
            return solve
        }

        return try {
            // Only the page being solved. A wildcard ran the probe in every frame on the page,
            // including third-party ones, and every report but the main frame's was discarded here
            // anyway. The feature check for the world belongs above, not around this call, which
            // throws when it is missing.
            val pageOnly = setOf(origin)
            val world = WebViewCompat.getExecutionWorld(webView, WORLD)

            WebViewCompat.addWebMessageListener(webView, BRIDGE, pageOnly, world) { _, m, _, main, _ ->
                if (main) m.data?.let(onWatch)
            }
            WebViewCompat.addJavaScriptOnEvent(
                webView,
                WATCH,
                WebViewCompat.INJECTION_EVENT_DOCUMENT_START,
                pageOnly,
                world,
            )
            solve
        } catch (e: Exception) {
            // Reporting armed here would suppress the caller's own aborts while nothing was left to
            // release the wait, costing the request its whole timeout rather than failing it.
            solve.cancel()
            logcat(LogPriority.ERROR, e) { "Turnstile[$host]: failed to arm, leaving the caller in charge" }
            null
        }
    }

    /**
     * Puts [webView] in the window off screen, which is what makes the widget render a checkbox and
     * take input at all.
     */
    private fun attachToWindow(webView: WebView, container: ViewGroup) {
        // Off screen rather than invisible: Chromium stops rendering a view it considers hidden, and
        // a widget that does not render is one that cannot be pressed. Shifted left by its own width
        // rather than right, so it stays off screen if the window grows under it.
        val width = container.width.takeIf { it > 0 } ?: DEFAULT_WIDTH
        val height = container.height.takeIf { it > 0 } ?: DEFAULT_HEIGHT
        webView.translationX = -width.toFloat()

        // It must never hold focus. Taking it on attach and handing it back on detach is what
        // reopens the soft keyboard over whatever the user was typing in, once per solve. The keys
        // are dispatched straight at the view rather than through the focus system, and they land
        // even while the soft keyboard is up and an app text field holds focus.
        webView.isFocusable = false
        webView.isFocusableInTouchMode = false
        webView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS

        container.addView(webView, ViewGroup.LayoutParams(width, height))
    }

    /**
     * Gives [webView] everything a window would, short of the window: a viewport, the visibility and
     * focus callbacks the view would otherwise be handed on attach, and focus itself.
     *
     * Focus is taken here where [attachToWindow] refuses it. In a window that would reopen the soft
     * keyboard over whatever the user was typing in; with no window there is nothing to take it from.
     */
    private fun layOutHeadless(webView: WebView) {
        val spec = { n: Int -> View.MeasureSpec.makeMeasureSpec(n, View.MeasureSpec.EXACTLY) }
        webView.measure(spec(DEFAULT_WIDTH), spec(DEFAULT_HEIGHT))
        webView.layout(0, 0, DEFAULT_WIDTH, DEFAULT_HEIGHT)
        webView.isFocusable = true
        webView.isFocusableInTouchMode = true
        webView.dispatchWindowVisibilityChanged(View.VISIBLE)
        webView.dispatchWindowFocusChanged(true)
        webView.requestFocus()
    }

    /** Takes the WebView back out of the window. Safe to call whether or not [attach] armed. */
    fun detach(webView: WebView) {
        (webView.parent as? ViewGroup)?.removeView(webView)
    }

    /**
     * Tabs onto the checkbox and hits Space, so no coordinate has to be estimated. Gaps are drawn
     * per event rather than held at mihonapp/mihon#3858's flat tenth of a second, since identical
     * timing across solves running at once is the one behavioural tell left on a key path that is
     * otherwise just typing. Posted rather than slept through so the gaps hold no thread. Returns
     * whether the first key was accepted, the only signal that the view took them at all.
     */
    private fun pressKeys(webView: WebView, solve: Solve): Boolean {
        val delivered = webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
        var at = nextGap()
        solve.post(at) { webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB)) }
        at += nextGap()
        solve.post(at) { webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)) }
        at += nextGap()
        solve.post(at) { webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)) }
        return delivered
    }

    private fun nextGap() = Random.nextLong(KEY_GAP_MIN_MS, KEY_GAP_MAX_MS)

    private const val DEFAULT_WIDTH = 1080
    private const val DEFAULT_HEIGHT = 1920
}

/**
 * Reports what the challenge page shows, twice a second and the moment Cloudflare says anything the
 * solver acts on, from a world its scripts cannot read.
 *
 * The interstitial keeps its response-token input even on a page that has passed, so the markup test
 * Solverr uses runs here rather than on the Kotlin side: sending the document across the bridge put a
 * parse and two full copies of it on the main thread twice a second, for one boolean.
 */
private val WATCH = """
(() => {
  'use strict';

  const TOKEN = 'input[name="cf-turnstile-response"]';
  const CHALLENGE = '#challenge-form,#challenge-stage,#cf-challenge-running,#cf-please-wait,#challenge-spinner';
  const CHALLENGE_ORIGIN = 'https://challenges.cloudflare.com';
  const DECIDING = ['interactiveBegin', 'complete'];
  // Ported from Solverr, including what it deliberately leaves out: Cloudflare leaves a
  // /cdn-cgi/challenge-platform/ beacon on solved pages too, so that string is not a marker.
  const MARKERS = [
    'window._cf_chl_opt', 'cf-challenge-running', 'id="challenge-form"',
    'id="challenge-stage"', 'id="challenge-error', 'turnstile-wrapper',
  ];

  // Cloudflare strips the challenge markup out of the interstitial before it navigates away, so a
  // page carrying none of it can still be the interstitial. Judge the document itself, the way
  // Solverr judges a body it is about to hand back.
  const looksLikeChallenge = () => {
    const low = document.documentElement.outerHTML.toLowerCase();
    const title = (low.split('<title>')[1] || '').split('</title>')[0];
    if (title.indexOf('just a moment') !== -1) return true;
    return MARKERS.some((m) => low.indexOf(m) !== -1);
  };

  let events = [];
  const report = () => {
    try {
      const input = document.querySelector(TOKEN);
      const markup = !!document.querySelector(CHALLENGE) || !!input;
      // A document with no body yet is mid-load, not a page that has cleared. Reading it as clear
      // twice was enough to reach Verified without a solve, which the caller trusts a late
      // clearance against.
      const challenged = markup || !document.body || looksLikeChallenge();
      const seen = events.join('|');
      events = [];
      window.reikaiTurnstileWatch.postMessage(JSON.stringify({
        challenged: challenged,
        token: input ? input.value : '',
        cf: seen,
      }));
    } catch (e) {}
  };

  // Cloudflare posts its own progress to the page, and it is readable from here, so the events that
  // decide anything need no script in the page's own world. The two that do decide something are
  // reported at once rather than held for the next tick: the page navigates away as soon as the
  // challenge passes, and a `complete` batched into a report that never fires dies with the
  // document, which cost a measured solve its fast accept. The per-second heartbeat still waits.
  addEventListener('message', (e) => {
    // The challenge posts from its own frame, which reports this origin on a live managed
    // challenge. Anything else on the page can forge the vocabulary, and a solve is the one thing
    // the caller trusts a clearance against.
    if (e.origin !== CHALLENGE_ORIGIN && e.origin !== location.origin) return;
    const d = e.data;
    if (!d || d.source !== 'cloudflare-challenge') return;
    // Only what the solve turns on. The rest of Cloudflare's vocabulary was collected, shipped
    // across the bridge and dropped unread; the caller's own bridge already logs all of it.
    if (DECIDING.indexOf(d.event) === -1) return;
    events.push(d.event);
    report();
  }, true);

  setInterval(report, 500);
})();
""".trimIndent()
