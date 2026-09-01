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
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * Presses the checkbox an interactive Cloudflare Turnstile challenge waits on.
 *
 * Sends Tab then Space, with a window or without one. A WebView that was never attached takes the
 * keys just as well once it has been laid out by hand and handed the focus callbacks; what used to
 * make that look impossible was `View.postDelayed` silently queueing every event after the first.
 * The page is read from an isolated world so nothing runs in the source's own. What was tried and
 * failed: docs/dev/plans/turnstile-solver.md.
 */
object TurnstileSolver {

    /** Byparr and Solverr both measured that pressing again mid-verification restarts it. */
    private const val PRESS_COOLDOWN_MS = 4000L
    private const val KEY_GAP_MIN_MS = 70L
    private const val KEY_GAP_MAX_MS = 160L

    /**
     * How long a solve gets after its first press before the request is failed. Key-path solves land
     * in 2.2 to 3.9 seconds, several hosts at once included, and the caller's own wait is 30, so this
     * fails a hopeless press sooner while leaving room for a challenge Cloudflare reissues once.
     */
    private const val PRESS_BUDGET_MS = 20_000L

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
     * The isolated world is as required as the other two: a probe in the page's own world is what
     * makes Cloudflare reissue the challenge, so without one there is nothing safe to watch from and
     * the solver declines rather than falling back into the page. Also keeps the switch off the
     * settings screen on a WebView that could never work.
     */
    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)

    /**
     * Starts working any Turnstile checkbox [webView] shows, calling [onSolved] once the challenge is
     * gone or [onGiveUp], which reports whether a wait was still open, when the solve runs out its
     * budget. Arming suppresses the caller's own aborts, so it owes one. Returns the [Solve] to read
     * the outcome from, or `null` when it did not arm.
     *
     * [origin] must read `scheme://host[:port]`, port only when not the scheme default. A script only
     * reaches later documents, so this runs before `loadUrl`; [detach] runs when the solve ends.
     */
    fun attach(
        webView: WebView,
        host: String,
        origin: String,
        backgroundEnabled: Boolean,
        onGiveUp: () -> Boolean,
        onSolved: () -> Boolean,
    ): Solve? {
        if (!isSupported) return null
        val container = ForegroundActivity.current?.window?.decorView.takeUnless { forceHeadless } as? ViewGroup
        // Solving with no app screen open is still the user's choice, since it means reaching a
        // challenged host while nothing is on screen to show for it. Not arming leaves the caller's
        // own aborts in charge.
        if (container == null && !backgroundEnabled) return null
        if (container != null) attachToWindow(webView, container) else layOutHeadless(webView)
        // Which press path ran is otherwise invisible in a log, and the two fail differently.
        logcat { "Turnstile[$host]: arming with${if (container == null) "out" else ""} a window" }

        val solve = Solve()
        var lastPress = 0L
        var clearReadings = 0
        var tokenSeen = false
        val budgetArmed = AtomicBoolean(false)

        val press = {
            val delivered = webView.pressKeys()
            logcat { "Turnstile[$host]: pressing tab+space (delivered $delivered)" }
            // The first press starts the only deadline this solve has. Arming suppresses the
            // caller's own aborts, so without it a press that goes nowhere, refused outright or
            // simply ineffective, costs the caller its whole 30 second wait. Report only a give-up
            // that released something: a request the caller already served from the jar leaves this
            // to fire into nothing, and a give-up line beside a success sends the next reader
            // hunting.
            if (budgetArmed.compareAndSet(false, true)) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (solve.phase != Solve.Phase.Accepted && onGiveUp()) {
                        logcat { "Turnstile[$host]: gave up $PRESS_BUDGET_MS ms after the first press" }
                    }
                }, PRESS_BUDGET_MS)
            }
        }

        val pressWhenDue = press@{ probe: JSONObject ->
            // Pressing belongs to exactly one phase. The widget is present from the first paint, well
            // before there is a checkbox behind it, and pressing then only starts the cooldown that
            // delays the press that counts; pressing after Cloudflare has said `complete` restarts
            // the verification it just finished. Both are unreachable from here rather than guarded.
            if (solve.phase != Solve.Phase.Interactive) return@press
            val now = SystemClock.uptimeMillis()
            // Never press a widget that already carries a token: that restarts the verification
            // Cloudflare is in the middle of rather than completing it.
            if (probe.optString("token").isNotEmpty() || now - lastPress < PRESS_COOLDOWN_MS) return@press

            lastPress = now
            press()
        }

        val onWatch = watch@{ json: String ->
            if (solve.phase == Solve.Phase.Accepted) return@watch
            val probe = runCatching { JSONObject(json) }.getOrNull() ?: return@watch

            // Cloudflare fills the response token the moment it accepts, so this splits a solve into
            // the part Cloudflare spent and the part spent noticing. Without it a slow solve cannot
            // be told from a fast one this was slow to see.
            if (!tokenSeen && probe.optString("token").isNotEmpty()) {
                tokenSeen = true
                logcat { "Turnstile[$host]: response token issued" }
            }

            val events = probe.optString("cf").split('|')

            // Read here rather than taken from the page-world listener, which cannot see this world
            // and needs the bridge to report anything. `fail` is deliberately not acted on while
            // armed: Cloudflare reissues after a failed round often enough that pressing through one
            // is the better bet, which is why the bridge only counts it down when the solver is off.
            if (solve.phase == Solve.Phase.Watching && INTERACTIVE_BEGIN in events) {
                solve.phase = Solve.Phase.Interactive
                logcat { "Turnstile[$host]: interactive began" }
            }

            // Cloudflare saying it accepted beats inferring it from the markup, and on a site that
            // embeds Turnstile on its own pages it is the only signal that can ever arrive: the
            // probe counts the response-token input as a challenge, so such a page never reads
            // clear and the markup path below never fires however many times it really succeeded.
            if (solve.phase != Solve.Phase.Verified && COMPLETE in events) {
                solve.phase = Solve.Phase.Verified
                logcat { "Turnstile[$host]: cloudflare reports the challenge complete" }
            }
            if (solve.phase == Solve.Phase.Verified) {
                // Only the acceptance is logged, since this runs twice a second until the clearance
                // lands and the caller takes it.
                if (onSolved()) {
                    solve.phase = Solve.Phase.Accepted
                    logcat { "Turnstile[$host]: challenge complete, accepted" }
                }
                return@watch
            }

            // Cloudflare strips the challenge markup out of the interstitial before it navigates
            // away, so a page carrying none of it can still be the interstitial. Judge the document
            // itself, the way Solverr judges a body it is about to hand back.
            if (probe.optBoolean("challenged", true) || looksLikeChallenge(probe.optString("html"))) {
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

        return try {
            // Only the page being solved. A wildcard ran the probe in every frame on the page,
            // including third-party ones, and every report but the main frame's was discarded here
            // anyway. The feature check for the world belongs in [isSupported], not around this
            // call, which throws when it is missing.
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
     * Whether [html] is still a challenge page rather than the content behind it.
     *
     * Ported from Solverr, including what it deliberately leaves out: Cloudflare leaves a
     * `/cdn-cgi/challenge-platform/` beacon on solved pages too, so that string is not a marker.
     */
    private fun looksLikeChallenge(html: String): Boolean {
        if (html.isEmpty()) return false
        val low = html.lowercase()
        val title = low.substringAfter("<title>", "").substringBefore("</title>")
        return "just a moment" in title || CHALLENGE_MARKERS.any { it in low }
    }

    /**
     * Tabs onto the checkbox and hits Space, so no coordinate has to be estimated. Gaps are drawn
     * per event rather than held at mihonapp/mihon#3858's flat tenth of a second, since identical
     * timing across solves running at once is the one behavioural tell left on a key path that is
     * otherwise just typing. Posted rather than slept through so the gaps hold no thread. Returns
     * whether the first key was accepted, the only signal that the view took them at all.
     */
    private fun WebView.pressKeys(): Boolean {
        val delivered = dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
        var at = nextGap()
        key(at, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB)
        at += nextGap()
        key(at, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)
        at += nextGap()
        key(at, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)
        return delivered
    }

    private fun nextGap() = Random.nextLong(KEY_GAP_MIN_MS, KEY_GAP_MAX_MS)

    /**
     * Handler, not `View.postDelayed`: a view that was never attached to a window queues its posts
     * into its own run queue and only runs them on attach, so with no window every event after the
     * first Tab silently never fired. That is what made a headless press look like a press Cloudflare
     * refused, when Space had simply never been sent.
     */
    private fun WebView.key(delay: Long, action: Int, code: Int) {
        Handler(Looper.getMainLooper()).postDelayed({ dispatchKeyEvent(KeyEvent(action, code)) }, delay)
    }

    private const val DEFAULT_WIDTH = 1080
    private const val DEFAULT_HEIGHT = 1920
}

private val CHALLENGE_MARKERS = listOf(
    "window._cf_chl_opt",
    "cf-challenge-running",
    "id=\"challenge-form\"",
    "id=\"challenge-stage\"",
    "id=\"challenge-error",
    "turnstile-wrapper",
)

/**
 * Reports what the challenge page shows, twice a second and the moment Cloudflare says anything the
 * solver acts on, from a world its scripts cannot read.
 *
 * The response token doubles as the challenge marker. Cloudflare's interstitial builds the widget
 * itself and that frame reports an empty URL, so the input is the only part of it the page can see.
 */
private val WATCH = """
(() => {
  'use strict';

  const TOKEN = 'input[name="cf-turnstile-response"]';
  const CHALLENGE = '#challenge-form,#challenge-stage,#cf-challenge-running,#cf-please-wait,#challenge-spinner';
  const MAX_BODY = 4000000;
  const DECIDING = ['interactiveBegin', 'complete'];

  let events = [];
  const report = () => {
    try {
      const input = document.querySelector(TOKEN);
      const challenged = !!document.querySelector(CHALLENGE) || !!input;
      const seen = events.join('|');
      events = [];
      // Enough of the document to tell an interstitial from the page behind it, which its markers
      // give away from the first paint. Waiting for readyState to reach complete instead cost a
      // whole solve on an image-heavy page that cleared its challenge and then kept loading.
      const html = challenged || !document.body ? '' : document.documentElement.outerHTML;
      window.reikaiTurnstileWatch.postMessage(JSON.stringify({
        challenged: challenged,
        token: input ? input.value : '',
        html: html.length > MAX_BODY ? '' : html,
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
    const d = e.data;
    if (!d || d.source !== 'cloudflare-challenge') return;
    events.push(d.event);
    if (DECIDING.indexOf(d.event) !== -1) report();
  }, true);

  setInterval(report, 500);
})();
""".trimIndent()
