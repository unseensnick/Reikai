package eu.kanade.tachiyomi.network.interceptor

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
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
 * With a window it attaches the WebView and sends Tab then Space. With none it injects a borrowed
 * script into the Cloudflare frame instead, since a detached widget takes no input. Never into the
 * source's own page: a script there gets the challenge reissued, which is also why the page is read
 * from an isolated world. What was tried and failed: docs/dev/plans/turnstile-solver.md.
 */
object TurnstileSolver {

    /** Byparr and Solverr both measured that pressing again mid-verification restarts it. */
    private const val PRESS_COOLDOWN_MS = 4000L
    private const val KEY_GAP_MIN_MS = 70L
    private const val KEY_GAP_MAX_MS = 160L

    /**
     * How long the in-frame fallback gets before the request is failed. Eleven measured solves landed
     * in 10.9 to 13.9 seconds, the slowest of them with five hosts solving at once, and the caller's
     * own wait is 30, so this fails a hopeless one sooner without threatening a slow working solve.
     */
    private const val FALLBACK_BUDGET_MS = 20_000L

    private const val BRIDGE = "reikaiTurnstileWatch"
    private const val WORLD = "reikai-turnstile"

    /**
     * Where the widget actually lives. Measured on a managed-challenge interstitial: the frame holding
     * the checkbox reports this origin, so the fallback can be scoped to it rather than to every frame.
     */
    private const val CHALLENGE_ORIGIN = "https://challenges.cloudflare.com"

    /** The placeholder the borrowed script names its own functions with, so it can hide their frames. */
    private const val SOLVER_TOKEN = "__SOLVER__"

    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    /**
     * Starts working any Turnstile checkbox [webView] shows, calling [onSolved] once the challenge
     * is really gone, or [onGiveUp] when the no-window path runs out of budget. Arming suppresses
     * the caller's own give-up signals, which is why it owes one. Returns whether it armed at all.
     *
     * Must run before `loadUrl`, since an injected script only reaches documents created after it is
     * registered. [detach] must run when the solve ends, however it ends.
     */
    fun attach(
        webView: WebView,
        host: String,
        interactive: AtomicBoolean,
        backgroundEnabled: Boolean,
        fallbackScript: () -> String,
        reload: () -> Unit,
        onGiveUp: () -> Unit,
        onSolved: () -> Boolean,
    ): Boolean {
        if (!isSupported) return false
        val container = ForegroundActivity.current?.window?.decorView as? ViewGroup
        // The in-frame script patches the challenge frame and fires synthetic events, where the key
        // path only types, so it carries its own switch. With that off and no window there is
        // nothing left to press with, and not arming leaves the caller's own aborts in charge.
        if (container == null && !backgroundEnabled) return false
        if (container != null) attachToWindow(webView, container)

        var lastPress = 0L
        var clearReadings = 0
        var solved = false
        val injected = AtomicBoolean(false)

        val press = if (container != null) {
            {
                val delivered = webView.pressKeys()
                logcat { "Turnstile[$host]: pressing tab+space (delivered $delivered)" }
            }
        } else {
            {
                // Once only: the script clicks on its own timer from then on, and re-registering it
                // would just reload the page out from under a solve already in progress.
                if (injected.compareAndSet(false, true)) {
                    logcat { "Turnstile[$host]: no window, injecting the in-frame solver" }
                    if (injectFallback(webView, host, fallbackScript())) {
                        reload()
                        // Nothing else reports a fallback that is never going to land, and without a
                        // budget it costs the caller's whole 30 second wait.
                        //
                        // Handler, not View.postDelayed: a detached view queues its posts until it is
                        // attached, and this path only runs when there is no window to attach to, so
                        // that timer would never have fired.
                        Handler(Looper.getMainLooper()).postDelayed({
                            if (!solved) {
                                logcat { "Turnstile[$host]: in-frame solver gave up after $FALLBACK_BUDGET_MS ms" }
                                onGiveUp()
                            }
                        }, FALLBACK_BUDGET_MS)
                    } else {
                        // A script that never registered will never press, so there is nothing to wait for.
                        onGiveUp()
                    }
                }
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
            if (++clearReadings >= 2) {
                solved = onSolved()
                logcat { "Turnstile[$host]: challenge cleared, accepted $solved" }
            }
        }

        try {
            val anyOrigin = setOf("*")
            val world = WebViewCompat.getExecutionWorld(webView, WORLD)
                .takeIf { WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD) }

            if (world != null) {
                WebViewCompat.addWebMessageListener(webView, BRIDGE, anyOrigin, world) { _, m, _, main, _ ->
                    if (main) m.data?.let(onWatch)
                }
                WebViewCompat.addJavaScriptOnEvent(
                    webView,
                    WATCH,
                    WebViewCompat.INJECTION_EVENT_DOCUMENT_START,
                    anyOrigin,
                    world,
                )
            } else {
                logcat { "Turnstile[$host]: no isolated world, watching from the page's own" }
                WebViewCompat.addWebMessageListener(webView, BRIDGE, anyOrigin) { _, m, _, main, _ ->
                    if (main) m.data?.let(onWatch)
                }
                WebViewCompat.addDocumentStartJavaScript(webView, WATCH, anyOrigin)
            }
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Failed to arm the Turnstile solver" }
        }
        return true
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
     * Registers the borrowed in-frame solver, scoped to the Cloudflare frame: a script in the source
     * page's own world is what makes Cloudflare reissue the challenge. It runs in that frame's own
     * world by necessity, since it patches the frame's `Error` and `EventTarget`.
     *
     * The script names every function it defines with one token so it can filter itself out of stack
     * traces, and that token is renamed per injection so the name cannot be matched on.
     */
    private fun injectFallback(webView: WebView, host: String, script: String): Boolean {
        val named = script.replace(SOLVER_TOKEN, "${SOLVER_TOKEN}_${Random.nextLong().toULong().toString(16)}")
        return runCatching {
            WebViewCompat.addDocumentStartJavaScript(webView, named, setOf(CHALLENGE_ORIGIN))
            true
        }.getOrElse {
            logcat(LogPriority.ERROR, it) { "Turnstile[$host]: failed to inject the in-frame solver" }
            false
        }
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

    private fun WebView.key(delay: Long, action: Int, code: Int) {
        postDelayed({ dispatchKeyEvent(KeyEvent(action, code)) }, delay)
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

  setInterval(() => {
    try {
      const input = document.querySelector(TOKEN);
      const challenged = !!document.querySelector(CHALLENGE) || !!input;
      // Enough of the document to tell an interstitial from the page behind it, which its markers
      // give away from the first paint. Waiting for readyState to reach complete instead cost a
      // whole solve on an image-heavy page that cleared its challenge and then kept loading.
      const html = challenged || !document.body ? '' : document.documentElement.outerHTML;
      window.reikaiTurnstileWatch.postMessage(JSON.stringify({
        challenged: challenged,
        token: input ? input.value : '',
        html: html.length > MAX_BODY ? '' : html,
      }));
    } catch (e) {}
  }, 500);
})();
""".trimIndent()
