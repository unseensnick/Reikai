package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.ForegroundActivity
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import eu.kanade.tachiyomi.util.system.setUserAgent
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat

/**
 * SPIKE ONLY, debug builds only. Bisects why a headless key press solves a Turnstile here and fails
 * through the solver, by running a clean detached WebView and then the same one with each of the
 * solver's four differences added, one at a time.
 *
 * Two targets. [Target.Dummy] renders Cloudflare's documented dummy sitekey
 * `3x00000000000000000000FF`, which forces an interactive widget on any domain and needs no live
 * challenge, so it is free to re-run. [Target.Live] loads a real URL and waits for a real managed
 * challenge. Everything is read from an isolated world, including Cloudflare's own challenge events.
 */
object TurnstileHarness {

    sealed interface Target {
        data object Dummy : Target

        /**
         * Candidate hosts, tried in order until one presents an interactive challenge. Whether a
         * host is challenging at all, and whether it challenges interactively rather than clearing
         * itself, changes with the exit and the hour, so one fixed host makes the sweep a coin flip.
         */
        data class Live(val urls: List<String>) : Target
    }

    /**
     * One solver difference, or the combination. `pageWorld` implies `bridge`: the injected listener
     * calls `mihon.*`, so it cannot be tested without the object it calls.
     */
    private data class Variant(
        val label: String,
        val attached: Boolean = false,
        val bridge: Boolean = false,
        val userAgent: Boolean = false,
        val pageWorld: Boolean = false,
        val bigProbe: Boolean = false,
    )

    private val CLEAN = Variant("detached-clean")
    private val ALL = Variant(
        "detached-all",
        bridge = true,
        userAgent = true,
        pageWorld = true,
        bigProbe = true,
    )
    private val SUSPECTS = listOf(
        Variant("detached-bridge", bridge = true),
        Variant("detached-ua", userAgent = true),
        Variant("detached-pageworld", bridge = true, pageWorld = true),
        Variant("detached-bigprobe", bigProbe = true),
    )
    private val ATTACHED_ALL = ALL.copy(label = "attached-all", attached = true)

    private const val DUMMY_ORIGIN = "https://reikai.test"
    private const val SITEKEY = "3x00000000000000000000FF"
    private const val RUN_MS = 30_000L
    private const val PHASE_GAP_MS = 1_000L
    private const val PRESS_COOLDOWN_MS = 4_000L
    private const val WIDTH = 1080
    private const val HEIGHT = 1920

    /** The solver's own default, spoofing a Chrome this WebView is not. */
    private const val SPOOFED_UA =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/149.0.0.0 Mobile Safari/537.36"

    private val PAGE = """
        <!doctype html><html><head><meta charset="utf-8"></head>
        <body style="margin:0">
          <div class="cf-turnstile" data-sitekey="$SITEKEY"></div>
          <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
        </body></html>
    """.trimIndent()

    /** The page-world listener `CloudflareInterceptor` injects on page finish, verbatim. */
    private val PAGE_WORLD_LISTENER = """
        addEventListener("message", ({data}) => {
            if (data?.source === "cloudflare-challenge" && data?.event === "interactiveBegin") {
                mihon.interactiveDetected();
            }
            if (data?.source === "cloudflare-challenge") {
                mihon.challengeEvent(String(data?.event));
            }
            if (data?.source === "cloudflare-challenge" && data?.event === "fail") {
                mihon.challengeFailed();
            }
        })
    """.trimIndent()

    private fun probeScript(bigProbe: Boolean) = """
        (() => {
          'use strict';
          const CHALLENGE = '#challenge-form,#challenge-stage,#cf-challenge-running,#cf-please-wait,#challenge-spinner';
          const MAX_BODY = 4000000;
          const BIG = $bigProbe;
          let keys = 0;
          let events = [];
          let interactive = false;
          addEventListener('keydown', () => { keys++; }, true);
          // Cloudflare posts its own progress to the page. Reading it from the isolated world means
          // no page-world script, which is the thing that makes the challenge reissue.
          addEventListener('message', (e) => {
            const d = e.data;
            if (d && d.source === 'cloudflare-challenge') {
              events.push(d.event);
              if (d.event === 'interactiveBegin') interactive = true;
            }
          }, true);

          const name = (el) => {
            if (!el) return 'none';
            if (el.tagName === 'IFRAME') {
              try { return 'IFRAME:' + new URL(el.src).host; } catch (e) { return 'IFRAME:?'; }
            }
            return el.tagName + (el.id ? '#' + el.id : '');
          };
          const deepActive = () => {
            const path = [];
            let el = document.activeElement;
            let guard = 0;
            while (el && guard++ < 8) {
              path.push(name(el));
              if (!el.shadowRoot || !el.shadowRoot.activeElement) break;
              el = el.shadowRoot.activeElement;
            }
            return path.join('>');
          };

          setInterval(() => {
            try {
              const input = document.querySelector('input[name="cf-turnstile-response"]');
              const div = document.querySelector('.cf-turnstile');
              const seen = events.join('|');
              events = [];
              const challenged = !!document.querySelector(CHALLENGE) || !!input;
              // Serialises the whole document and posts it across the bridge twice a second, which
              // is what the solver's WATCH used to do before that test moved into the script. Kept
              // as the control for the sweep that eliminated it as a suspect.
              let html = '';
              if (BIG) {
                html = challenged || !document.body ? '' : document.documentElement.outerHTML;
                if (html.length > MAX_BODY) html = '';
              }
              window.reikaiHarness.postMessage(JSON.stringify({
                token: input ? input.value : '',
                challenged: challenged,
                widget: div ? (div.childElementCount + ':' + Math.round(div.getBoundingClientRect().width)) : '-',
                keys: keys,
                focus: document.hasFocus(),
                active: deepActive(),
                cf: seen,
                interactive: interactive,
                title: (document.title || '').slice(0, 40),
                html: html,
              }));
            } catch (e) {}
          }, 500);
        })();
    """.trimIndent()

    /**
     * How a phase ended. Only [Solved] and [Failed] say anything about the press. [NoPress] says the
     * target cannot answer the question, which is a fact about the host rather than about the
     * variant under test, so it aborts the sweep instead of reading as a result.
     */
    private enum class Outcome {
        /** A press produced a response token. */
        Solved,

        /** The challenge turned interactive, was pressed, and no token followed. */
        Failed,

        /**
         * Nothing was pressed, so the phase measured nothing about the press: no challenge was
         * issued, or one was and it never turned interactive. The phase's own log line says which.
         */
        NoPress,
    }

    /**
     * Runs the sweep. The clean run doubles as the search for a usable host, since a host that
     * presses nothing cannot answer the bisect whatever variant is on. Then everything the solver
     * adds at once: if that presses through, the target does not reproduce the failure and the four
     * suspects are not worth running, so the sweep stops and says so.
     */
    fun run(context: Context, target: Target) {
        val candidates = when (target) {
            Target.Dummy -> listOf(target)
            is Target.Live -> target.urls.map { Target.Live(listOf(it)) }
        }
        Handler(Looper.getMainLooper()).post { findHost(context, candidates) }
    }

    private fun findHost(context: Context, candidates: List<Target>) {
        val site = candidates.firstOrNull() ?: run {
            logcat(LogPriority.WARN) {
                "Harness: no candidate pressed anything, so none of them is issuing an interactive " +
                    "challenge on this exit right now. Nothing was measured."
            }
            return
        }
        once(context, site, CLEAN) { clean ->
            if (clean == Outcome.NoPress) {
                findHost(context, candidates.drop(1))
                return@once
            }
            once(context, site, ALL) { all ->
                if (all == Outcome.NoPress) {
                    logcat(LogPriority.WARN) {
                        "Harness: the host stopped presenting an interactive challenge mid-sweep, " +
                            "so detached-all measured nothing. Re-run."
                    }
                    return@once
                }
                if (all == Outcome.Solved) {
                    logcat(LogPriority.WARN) {
                        "Harness: detached-all solved, so this target does not reproduce the " +
                            "solver's headless failure."
                    }
                    return@once
                }
                chain(context, site, SUSPECTS + ATTACHED_ALL)
            }
        }
    }

    private fun chain(context: Context, target: Target, remaining: List<Variant>) {
        val next = remaining.firstOrNull() ?: run {
            logcat { "Harness: sweep done" }
            return
        }
        once(context, target, next) { chain(context, target, remaining.drop(1)) }
    }

    private fun once(
        context: Context,
        target: Target,
        variant: Variant,
        onDone: (Outcome) -> Unit,
    ) {
        val label = variant.label
        // The feature set itself, never the solver's debug override: reading that made leaving the
        // force-the-no-isolated-world row on abort every phase as an unsupported WebView. A device
        // that lacks it lacks it for every phase, so this ends the sweep rather than handing it on.
        if (!TurnstileSolver.hasIsolatedWorld) {
            logcat(LogPriority.ERROR) { "Harness[$label]: webview lacks the required features, sweep over" }
            return
        }
        val origin = when (target) {
            Target.Dummy -> DUMMY_ORIGIN
            is Target.Live -> target.urls.first().toHttpOriginOrNull() ?: run {
                logcat(LogPriority.ERROR) { "Harness[$label]: cannot derive an origin from ${target.urls.first()}" }
                onDone(Outcome.NoPress)
                return
            }
        }
        // A phase that cannot run hands the sweep on rather than stalling it silently.
        val container = if (variant.attached) {
            ForegroundActivity.current?.window?.decorView as? ViewGroup ?: run {
                logcat(LogPriority.ERROR) { "Harness[$label]: SKIPPED, no foreground window" }
                onDone(Outcome.NoPress)
                return
            }
        } else {
            null
        }

        // Every phase has to face its own challenge. Without this the second one has nothing to
        // solve, because the first already earned the clearance.
        clearCookies(origin)

        val main = Handler(Looper.getMainLooper())
        var last = ""
        var lastPress = 0L
        var done = false
        var sawChallenge = false
        var sawInteractive = false
        var sawComplete = false
        var outcome = Outcome.NoPress
        val started = SystemClock.uptimeMillis()

        val webView = WebView(context).apply {
            setDefaultSettings()
            if (variant.userAgent) setUserAgent(SPOOFED_UA)
            // Without a client, WebView hands every navigation to the system and the load leaves for
            // whatever browser is installed. The real interceptor sets one; so must this.
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    if (variant.pageWorld) view.evaluateJavascript(PAGE_WORLD_LISTENER, null)
                }
            }
        }
        if (variant.bridge) webView.addJavascriptInterface(bridgeObject(label), "mihon")

        lateinit var finish: (String) -> Unit

        try {
            val world = WebViewCompat.getExecutionWorld(webView, "reikai-harness")
            val origins = setOf(origin)
            WebViewCompat.addWebMessageListener(webView, "reikaiHarness", origins, world) { _, m, _, isMain, _ ->
                if (!isMain || done) return@addWebMessageListener
                val probe = runCatching { JSONObject(m.data ?: return@addWebMessageListener) }.getOrNull()
                    ?: return@addWebMessageListener

                val cf = probe.optString("cf")
                if (cf.isNotEmpty()) logcat { "Harness[$label]: cf $cf" }
                if ("complete" in cf.split('|')) sawComplete = true

                val token = probe.optString("token")
                val challenged = probe.optBoolean("challenged")
                if (challenged) sawChallenge = true
                if (probe.optBoolean("interactive")) sawInteractive = true

                // The token is a live credential for this challenge, so its length is logged and
                // its value is not.
                val diag = "token=${if (token.isEmpty()) "-" else "${token.length}ch"} " +
                    "challenged=$challenged widget=${probe.optString("widget")} " +
                    "keys=${probe.optInt("keys")} focus=${probe.optBoolean("focus")} " +
                    "active=${probe.optString("active")} html=${probe.optString("html").length} " +
                    "title=${probe.optString("title")}"
                if (diag != last) {
                    last = diag
                    logcat { "Harness[$label]: $diag" }
                }

                // A managed-challenge interstitial never fills the response token in the main frame:
                // measured on a real one, the input stayed empty through `complete` and through the
                // navigation to the real page. So `complete` plus the interstitial going is the only
                // signal that works on both an interstitial and a page-embedded widget, and reading
                // the token alone reports a solve that happened as a failure.
                if (token.isNotEmpty() || (sawComplete && !challenged)) {
                    outcome = Outcome.Solved
                    finish("SOLVED in ${SystemClock.uptimeMillis() - started}ms")
                    return@addWebMessageListener
                }

                // A challenge that clears itself was never pressed, so this phase measured nothing
                // about the press. Ending here rather than at the budget keeps a host that is not
                // issuing an interactive challenge from costing the sweep 30 seconds a phase.
                if (sawChallenge && !challenged && !sawInteractive) {
                    outcome = Outcome.NoPress
                    finish("CLEARED without turning interactive in ${SystemClock.uptimeMillis() - started}ms")
                    return@addWebMessageListener
                }

                // Dummy renders its widget straight away; a live challenge has to say it turned
                // interactive first, exactly as the real solver waits.
                val ready = when (target) {
                    Target.Dummy -> probe.optString("widget") != "-"
                    is Target.Live -> sawInteractive
                }
                if (ready) outcome = Outcome.Failed
                val now = SystemClock.uptimeMillis()
                if (ready && now - lastPress > PRESS_COOLDOWN_MS) {
                    lastPress = now
                    main.post { press(webView, main, label) { !done } }
                }
            }
            WebViewCompat.addJavaScriptOnEvent(
                webView,
                probeScript(variant.bigProbe),
                WebViewCompat.INJECTION_EVENT_DOCUMENT_START,
                origins,
                world,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Harness[$label]: SKIPPED, failed to arm" }
            webView.destroy()
            onDone(Outcome.NoPress)
            return
        }

        finish = { verdict ->
            if (!done) {
                done = true
                logcat { "Harness[$label]: $verdict" }
                (webView.parent as? ViewGroup)?.removeView(webView)
                webView.stopLoading()
                webView.destroy()
                main.postDelayed({ onDone(outcome) }, PHASE_GAP_MS)
            }
        }

        if (container != null) {
            webView.translationX = -WIDTH.toFloat()
            webView.isFocusable = false
            webView.isFocusableInTouchMode = false
            webView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
            container.addView(webView, ViewGroup.LayoutParams(WIDTH, HEIGHT))
        } else {
            val spec = { n: Int -> View.MeasureSpec.makeMeasureSpec(n, View.MeasureSpec.EXACTLY) }
            webView.measure(spec(WIDTH), spec(HEIGHT))
            webView.layout(0, 0, WIDTH, HEIGHT)
            webView.isFocusable = true
            webView.isFocusableInTouchMode = true
            webView.dispatchWindowVisibilityChanged(View.VISIBLE)
            webView.dispatchWindowFocusChanged(true)
            webView.requestFocus()
        }

        logcat { "Harness[$label]: starting $origin, attached=${container != null}" }
        when (target) {
            Target.Dummy -> webView.loadDataWithBaseURL("$DUMMY_ORIGIN/", PAGE, "text/html", "utf-8", null)
            is Target.Live -> webView.loadUrl(target.urls.first())
        }

        main.postDelayed({
            finish(
                when {
                    !sawChallenge -> "NO CHALLENGE in ${RUN_MS}ms, the page loaded straight through"
                    !sawInteractive -> "CLEARED without turning interactive, nothing was pressed"
                    else -> "FAILED, pressed and never accepted in ${RUN_MS}ms"
                },
            )
        }, RUN_MS)
    }

    /** The `mihon` object `CloudflareInterceptor` puts on every frame, logging rather than acting. */
    private fun bridgeObject(label: String) = object {
        @Suppress("unused")
        @JavascriptInterface
        fun interactiveDetected() {
            logcat { "Harness[$label]: bridge interactiveDetected" }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun challengeFailed() {
            logcat { "Harness[$label]: bridge challengeFailed" }
        }

        @Suppress("unused")
        @JavascriptInterface
        fun challengeEvent(event: String) {
            logcat { "Harness[$label]: bridge cf event $event" }
        }
    }

    /** Expires every cookie [origin] holds, so each phase faces its own challenge. */
    private fun clearCookies(origin: String) {
        val cleared = AndroidCookieJar().remove(origin.toHttpUrl(), maxAge = 0)
        CookieManager.getInstance().flush()
        logcat { "Harness: cleared $cleared cookies on $origin" }
    }

    /** [alive] is false once the phase has torn its WebView down, since a key on a destroyed one is not a press. */
    private fun press(webView: WebView, main: Handler, label: String, alive: () -> Boolean) {
        fun send(action: Int, code: Int) {
            if (alive()) webView.dispatchKeyEvent(KeyEvent(action, code))
        }
        send(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB)
        main.postDelayed({ send(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB) }, 100)
        main.postDelayed({ send(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE) }, 200)
        main.postDelayed({
            send(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE)
            logcat { "Harness[$label]: pressed tab+space" }
        }, 300)
    }

    private fun String.toHttpOriginOrNull(): String? = runCatching {
        val url = java.net.URI(this)
        val port = url.port
        val scheme = url.scheme ?: return null
        val host = url.host ?: return null
        if (port < 0 || (scheme == "https" && port == 443) || (scheme == "http" && port == 80)) {
            "$scheme://$host"
        } else {
            "$scheme://$host:$port"
        }
    }.getOrNull()
}
