package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import eu.kanade.tachiyomi.util.system.ForegroundActivity
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import logcat.LogPriority
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat

/**
 * SPIKE ONLY, debug builds only. Presses a Turnstile in a WebView that is deliberately never attached
 * to a window, and again in one that is, so the two can be diffed.
 *
 * Two targets. [Target.Dummy] renders Cloudflare's documented dummy sitekey
 * `3x00000000000000000000FF`, which forces an interactive widget on any domain and needs no live
 * challenge, so it is free to re-run. [Target.Live] loads a real URL and waits for a real managed
 * challenge. Everything is read from an isolated world, including Cloudflare's own challenge events.
 */
object TurnstileHarness {

    sealed interface Target {
        data object Dummy : Target
        data class Live(val url: String) : Target
    }

    private const val DUMMY_ORIGIN = "https://reikai.test"
    private const val SITEKEY = "3x00000000000000000000FF"
    private const val RUN_MS = 30_000L
    private const val PRESS_COOLDOWN_MS = 4_000L
    private const val WIDTH = 1080
    private const val HEIGHT = 1920

    private val PAGE = """
        <!doctype html><html><head><meta charset="utf-8"></head>
        <body style="margin:0">
          <div class="cf-turnstile" data-sitekey="$SITEKEY"></div>
          <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
        </body></html>
    """.trimIndent()

    private val PROBE = """
        (() => {
          'use strict';
          const CHALLENGE = '#challenge-form,#challenge-stage,#cf-challenge-running,#cf-please-wait,#challenge-spinner';
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
              window.reikaiHarness.postMessage(JSON.stringify({
                token: input ? input.value : '',
                challenged: !!document.querySelector(CHALLENGE) || !!input,
                widget: div ? (div.childElementCount + ':' + Math.round(div.getBoundingClientRect().width)) : '-',
                keys: keys,
                focus: document.hasFocus(),
                active: deepActive(),
                cf: seen,
                interactive: interactive,
                title: (document.title || '').slice(0, 40),
              }));
            } catch (e) {}
          }, 500);
        })();
    """.trimIndent()

    /** Runs [target] detached, then attached, logging both. */
    fun run(context: Context, target: Target) {
        val main = Handler(Looper.getMainLooper())
        main.post {
            once(context, target, "detached", null)
            main.postDelayed(
                {
                    once(
                        context,
                        target,
                        "attached",
                        ForegroundActivity.current?.window?.decorView as? ViewGroup,
                    )
                },
                RUN_MS + 1_000L,
            )
        }
    }

    private fun once(context: Context, target: Target, label: String, container: ViewGroup?) {
        if (!TurnstileSolver.isSupported) {
            logcat(LogPriority.ERROR) { "Harness[$label]: webview lacks the required features" }
            return
        }
        val origin = when (target) {
            Target.Dummy -> DUMMY_ORIGIN
            is Target.Live -> target.url.toHttpOriginOrNull() ?: run {
                logcat(LogPriority.ERROR) { "Harness[$label]: cannot derive an origin from ${target.url}" }
                return
            }
        }
        val webView = WebView(context).apply {
            setDefaultSettings()
            // Without a client, WebView hands every navigation to the system and the load leaves for
            // whatever browser is installed. The real interceptor sets one; so must this.
            webViewClient = android.webkit.WebViewClient()
        }
        val main = Handler(Looper.getMainLooper())
        var last = ""
        var lastPress = 0L
        var done = false

        try {
            val world = WebViewCompat.getExecutionWorld(webView, "reikai-harness")
            val origins = setOf(origin)
            WebViewCompat.addWebMessageListener(webView, "reikaiHarness", origins, world) { _, m, _, isMain, _ ->
                if (!isMain || done) return@addWebMessageListener
                val probe = runCatching { JSONObject(m.data ?: return@addWebMessageListener) }.getOrNull()
                    ?: return@addWebMessageListener

                val cf = probe.optString("cf")
                if (cf.isNotEmpty()) logcat { "Harness[$label]: cf $cf" }

                val token = probe.optString("token")
                val diag = "token=${if (token.isEmpty()) "-" else token} " +
                    "challenged=${probe.optBoolean("challenged")} widget=${probe.optString("widget")} " +
                    "keys=${probe.optInt("keys")} focus=${probe.optBoolean("focus")} " +
                    "active=${probe.optString("active")} title=${probe.optString("title")}"
                if (diag != last) {
                    last = diag
                    logcat { "Harness[$label]: $diag" }
                }

                // Dummy renders its widget straight away; a live challenge has to say it turned
                // interactive first, exactly as the real solver waits.
                val ready = when (target) {
                    Target.Dummy -> probe.optString("widget") != "-"
                    is Target.Live -> probe.optBoolean("interactive")
                }
                val now = android.os.SystemClock.uptimeMillis()
                if (ready && token.isEmpty() && now - lastPress > PRESS_COOLDOWN_MS) {
                    lastPress = now
                    main.post { press(webView, main, label) }
                }
            }
            WebViewCompat.addJavaScriptOnEvent(
                webView,
                PROBE,
                WebViewCompat.INJECTION_EVENT_DOCUMENT_START,
                origins,
                world,
            )
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "Harness[$label]: failed to arm" }
            webView.destroy()
            return
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
            is Target.Live -> webView.loadUrl(target.url)
        }

        main.postDelayed({
            done = true
            logcat { "Harness[$label]: done" }
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }, RUN_MS)
    }

    private fun press(webView: WebView, main: Handler, label: String) {
        webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
        main.postDelayed({ webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB)) }, 100)
        main.postDelayed({ webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)) }, 200)
        main.postDelayed({
            webView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_SPACE))
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
