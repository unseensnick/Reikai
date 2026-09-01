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
     * How long a solve with no window gets before the request is failed. Eleven measured solves
     * landed in 10.9 to 13.9 seconds, the slowest of them with five hosts solving at once, and the
     * caller's own wait is 30, so this fails a hopeless one sooner without threatening a slow one.
     */
    private const val HEADLESS_BUDGET_MS = 20_000L

    /** The event Cloudflare posts when it has accepted a challenge. */
    private const val COMPLETE = "complete"

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
     * gone or [onGiveUp], which reports whether a wait was still open, when a solve with no window
     * runs out its budget. Arming suppresses the caller's own aborts, so it owes one. Returns
     * whether it armed.
     *
     * [origin] must read `scheme://host[:port]`, port only when not the scheme default. A script only
     * reaches later documents, so this runs before `loadUrl`; [detach] runs when the solve ends.
     */
    fun attach(
        webView: WebView,
        host: String,
        origin: String,
        interactive: AtomicBoolean,
        interstitialGone: AtomicBoolean,
        backgroundEnabled: Boolean,
        onGiveUp: () -> Boolean,
        onSolved: () -> Boolean,
    ): Boolean {
        if (!isSupported) return false
        val container = ForegroundActivity.current?.window?.decorView.takeUnless { forceHeadless } as? ViewGroup
        // Solving with no app screen open is still the user's choice, since it means reaching a
        // challenged host while nothing is on screen to show for it. Not arming leaves the caller's
        // own aborts in charge.
        if (container == null && !backgroundEnabled) return false
        if (container != null) attachToWindow(webView, container) else layOutHeadless(webView)
        // Which press path ran is otherwise invisible in a log, and the two fail differently.
        logcat { "Turnstile[$host]: arming with${if (container == null) "out" else ""} a window" }

        var lastPress = 0L
        var clearReadings = 0
        var solved = false
        var tokenSeen = false
        var completeSeen = false
        val budgetArmed = AtomicBoolean(false)

        val press = {
            val delivered = webView.pressKeys()
            logcat { "Turnstile[$host]: pressing tab+space (delivered $delivered)" }
            // With a window the user is watching and the caller's own wait bounds the solve. With
            // none, nothing does, so the first press starts a budget rather than letting a press
            // that goes nowhere cost the caller its whole timeout. Report only a give-up that
            // released something: a request the caller already served from the jar leaves this to
            // fire into nothing, and a give-up line beside a success sends the next reader hunting.
            if (container == null && budgetArmed.compareAndSet(false, true)) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!solved && onGiveUp()) {
                        logcat { "Turnstile[$host]: gave up after $HEADLESS_BUDGET_MS ms with no window" }
                    }
                }, HEADLESS_BUDGET_MS)
            }
        }

        val pressWhenDue = press@{ probe: JSONObject ->
            // The widget is present from the first paint, well before there is a checkbox behind it.
            // Pressing then does nothing except start the cooldown, which delayed the press that
            // counts by two seconds on every measured solve.
            if (!interactive.get()) return@press
            val now = SystemClock.uptimeMillis()
            // Never press a widget that already carries a token: that restarts the verification
            // Cloudflare is in the middle of rather than completing it.
            if (probe.optString("token").isNotEmpty() || now - lastPress < PRESS_COOLDOWN_MS) return@press

            lastPress = now
            press()
        }

        val onWatch = watch@{ json: String ->
            if (solved) return@watch
            val probe = runCatching { JSONObject(json) }.getOrNull() ?: return@watch

            // Cloudflare fills the response token the moment it accepts, so this splits a solve into
            // the part Cloudflare spent and the part spent noticing. Without it a slow solve cannot
            // be told from a fast one this was slow to see.
            if (!tokenSeen && probe.optString("token").isNotEmpty()) {
                tokenSeen = true
                logcat { "Turnstile[$host]: response token issued" }
            }

            // Cloudflare saying it accepted beats inferring it from the markup, and on a site that
            // embeds Turnstile on its own pages it is the only signal that can ever arrive: the
            // probe counts the response-token input as a challenge, so such a page never reads
            // clear and the solve below never fires however many times it really succeeded.
            if (!completeSeen && COMPLETE in probe.optString("cf").split('|')) {
                completeSeen = true
                logcat { "Turnstile[$host]: cloudflare reports the challenge complete" }
            }
            if (completeSeen) {
                interstitialGone.set(true)
                solved = onSolved()
                // Only the acceptance is logged, since this runs twice a second until the clearance
                // lands and the caller takes it.
                if (solved) logcat { "Turnstile[$host]: challenge complete, accepted" }
                // Cloudflare is done either way, and pressing again restarts the verification it
                // has just finished, so this never falls through to the press below.
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
                // Records that the page really did get past the interstitial, which is what the
                // caller needs before it may read anything into a clearance arriving late.
                interstitialGone.set(true)
                solved = onSolved()
                logcat { "Turnstile[$host]: challenge cleared, accepted $solved" }
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
            true
        } catch (e: Exception) {
            // Reporting armed here would suppress the caller's own aborts while nothing was left to
            // release the wait, costing the request its whole timeout rather than failing it.
            logcat(LogPriority.ERROR, e) { "Turnstile[$host]: failed to arm, leaving the caller in charge" }
            false
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
 * Reports what the challenge page shows, twice a second, from a world its scripts cannot read.
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

  // Cloudflare posts its own progress to the page, and it is readable from here, so the events that
  // decide anything need no script in the page's own world. Collected between reports rather than
  // forwarded one at a time, since the report is already on a timer.
  let events = [];
  addEventListener('message', (e) => {
    const d = e.data;
    if (d && d.source === 'cloudflare-challenge') events.push(d.event);
  }, true);

  setInterval(() => {
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
  }, 500);
})();
""".trimIndent()
