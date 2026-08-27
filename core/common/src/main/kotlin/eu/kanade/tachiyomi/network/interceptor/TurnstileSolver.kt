package eu.kanade.tachiyomi.network.interceptor

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
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Presses the checkbox of an interactive Cloudflare Turnstile challenge in the bypass WebView.
 *
 * The WebView has to be attached to a window: detached it renders no checkbox and takes no input.
 * The press is an ordinary [KeyEvent] pair, Tab then Space, so no DOM patching and no coordinate is
 * needed, and page state is read from an isolated JS world, because a script in the page's own world
 * gets the challenge reissued. What else was tried, and failed, is in docs/dev/plans/turnstile-solver.md.
 */
object TurnstileSolver {

    /** Byparr and Solverr both measured that pressing again mid-verification restarts it. */
    private const val PRESS_COOLDOWN_MS = 4000L
    private const val KEY_GAP_MS = 100L

    private const val BRIDGE = "reikaiTurnstileWatch"
    private const val SIBLING_WAIT_SECONDS = 60L
    private const val WORLD = "reikai-turnstile"

    val isSupported: Boolean
        get() = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)

    private val pendingByHost = ConcurrentHashMap<String, CompletableFuture<Unit>>()

    /**
     * Runs [solve] once per host, returning null when another thread was already solving that host.
     * A null tells the caller to retry with whatever the cookie jar now holds instead of queueing
     * for a window it does not need: a page that fires two challenged requests only has to be
     * solved once, and the second was costing a turn another host was waiting for.
     *
     * Mirrors `FlareSolverrClient.resolveWithFlareSolverrDedup`, which dedupes the same way.
     */
    fun <T> onlyOncePerHost(host: String, solve: () -> T): T? {
        while (true) {
            pendingByHost[host]?.let { inFlight ->
                logcat { "Turnstile[$host]: another request is solving this host, waiting" }
                runCatching { inFlight.get(SIBLING_WAIT_SECONDS, TimeUnit.SECONDS) }
                return null
            }
            val mine = CompletableFuture<Unit>()
            if (pendingByHost.putIfAbsent(host, mine) == null) {
                try {
                    return solve().also { mine.complete(Unit) }
                } catch (e: Exception) {
                    mine.completeExceptionally(e)
                    throw e
                } finally {
                    pendingByHost.remove(host)
                }
            }
            // Another thread won the race between the read and the claim; look again.
        }
    }

    /**
     * Attaches [webView] to the foreground window and starts pressing any Turnstile checkbox it
     * finds, calling [onSolved] once the challenge is really gone. Returns whether it armed: with no
     * activity on screen there is no window to attach to, so the caller keeps its usual behaviour.
     *
     * Must run before `loadUrl`, since an injected script only reaches documents created after it is
     * registered. [detach] must run when the solve ends, however it ends.
     */
    fun attach(
        webView: WebView,
        host: String,
        interactive: AtomicBoolean,
        onSolved: () -> Boolean,
    ): Boolean {
        if (!isSupported) return false
        val container = ForegroundActivity.current?.window?.decorView as? ViewGroup ?: return false

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

        var lastPress = 0L
        var clearReadings = 0
        var solved = false

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
            val delivered = webView.pressKeys()
            logcat { "Turnstile[$host]: pressing tab+space (delivered $delivered)" }
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
     * Tabs onto the checkbox and hits Space, so no coordinate has to be estimated. The cadence is
     * mihonapp/mihon#3858's, a tenth of a second between every event, dispatched from the main
     * thread rather than a sleeping one because a WebView takes calls from nowhere else. Returns
     * whether the first key was accepted, the only signal that the view took them at all.
     */
    private fun WebView.pressKeys(): Boolean {
        val delivered = dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
        key(KEY_GAP_MS, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB)
        key(KEY_GAP_MS * 2, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)
        key(KEY_GAP_MS * 3, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)
        return delivered
    }

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
