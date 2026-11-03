package eu.kanade.tachiyomi.network.interceptor

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import tachiyomi.core.common.util.system.logcat
import java.io.File

/**
 * Debug builds only. Runs the real [TurnstileSolver] on Cloudflare's test widget (served on a made-up
 * origin, which the test sitekeys allow) or on a real challenged site, and logs what the solver cannot
 * see: where focus sits up to the widget's frame, Cloudflare's events and the response token.
 *
 * The test widget never refuses a press, so only a real site shows a reissued round. The force rows
 * reach the solver's other paths. Read with `adb logcat -s TurnstileBench TurnstileSolver`.
 */
object TurnstileBench {

    /** Cloudflare's documented test key that always asks for the checkbox. */
    const val INTERACTIVE_SITEKEY = "3x00000000000000000000FF"

    /** Cloudflare's documented test key that always fails, without ever showing a checkbox. */
    const val REFUSING_SITEKEY = "2x00000000000000000000AB"

    private const val HOST = "reikai.test"
    private const val ORIGIN = "https://$HOST"
    private const val WORLD = "reikai-bench"
    private const val RUN_MS = 35_000L

    private fun page(sitekey: String) = """
        <!doctype html><html><head><meta charset="utf-8"><title>Bench</title></head>
        <body style="margin:0">
          <div class="cf-turnstile" data-sitekey="$sitekey"></div>
          <script src="https://challenges.cloudflare.com/turnstile/v0/api.js" async defer></script>
        </body></html>
    """.trimIndent()

    // Read only: the path to the focused element, crossing into an open shadow root but not into the
    // widget's cross-origin frame, which shows as the frame itself.
    private val PROBE = """
        (() => {
          'use strict';
          let events = [];
          addEventListener('message', (e) => {
            const d = e.data;
            if (d && d.source === 'cloudflare-challenge') events.push(d.event);
          }, true);
          const name = (el) => !el ? 'none' : el.tagName === 'IFRAME'
            ? 'IFRAME:' + (() => { try { return new URL(el.src).host; } catch (e) { return '?'; } })()
            : el.tagName + (el.id ? '#' + el.id : '');
          const active = () => {
            const path = [];
            let el = document.activeElement;
            for (let i = 0; el && i < 8; i++) {
              path.push(name(el));
              el = el.shadowRoot && el.shadowRoot.activeElement;
            }
            return path.join('>');
          };
          setInterval(() => {
            try {
              const input = document.querySelector('input[name="cf-turnstile-response"]');
              const seen = events.join('|');
              events = [];
              window.reikaiBench.postMessage(JSON.stringify({
                active: active(),
                focus: document.hasFocus(),
                token: input ? input.value.length : -1,
                cf: seen,
              }));
            } catch (e) {}
          }, 500);
        })();
    """.trimIndent()

    fun run(context: Context, sitekey: String = INTERACTIVE_SITEKEY) {
        Handler(Looper.getMainLooper()).post {
            start(context, HOST, ORIGIN, { webView ->
                log("loading the test widget, sitekey $sitekey")
                webView.loadDataWithBaseURL(ORIGIN, page(sitekey), "text/html", "utf-8", null)
            }) { false } // The test widget issues a token but never a clearance, so there is nothing to accept.
        }
    }

    private var nextSite = 0

    /**
     * Runs the solver on the next address in `bench-urls.txt` in the app's external files folder (one
     * per line, pushed with adb), dropping that host's clearance first so the site challenges again.
     * Returns the address, or null when the file has none.
     */
    fun runOnNextSite(context: Context): String? {
        val urls = runCatching { File(context.getExternalFilesDir(null), "bench-urls.txt").readLines() }
            .getOrDefault(emptyList())
            .map(String::trim)
            .filter { it.startsWith("https://") }
        if (urls.isEmpty()) return null
        val url = urls[nextSite++ % urls.size]
        val uri = Uri.parse(url)
        val origin = "https://${uri.host}"
        val cookies = CookieManager.getInstance()
        val hasClearance = { cookies.getCookie(url)?.contains("$CF_CLEARANCE=") == true }
        AndroidCookieJar().remove(url.toHttpUrl(), listOf(CF_CLEARANCE))
        Handler(Looper.getMainLooper()).postDelayed({
            // Logged, so a jar that fails to clear shows as such rather than as a site that stopped
            // challenging.
            log("clearance left after dropping it: ${hasClearance()}")
            start(context, uri.host!!, origin, { webView ->
                log("loading $origin")
                webView.loadUrl(url)
            }, hasClearance)
        }, 300)
        return url
    }

    private fun start(
        context: Context,
        host: String,
        origin: String,
        load: (WebView) -> Unit,
        onSolved: () -> Boolean,
    ) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.JS_INJECTION_IN_FRAME_AND_WORLD)) {
            log("this WebView has no isolated world, so nothing can be read without disturbing the page")
            return
        }
        val webView = WebView(context).apply {
            setDefaultSettings()
            webViewClient = WebViewClient()
        }
        val solve = TurnstileSolver.attach(
            webView,
            host,
            origin,
            backgroundEnabled = true,
            onGiveUp = {
                log("solver gave up")
                true
            },
            onSolved = onSolved,
        )
        if (solve == null) {
            log("the solver did not arm")
            webView.destroy()
            return
        }

        var last = ""
        val world = WebViewCompat.getExecutionWorld(webView, WORLD)
        WebViewCompat.addWebMessageListener(webView, "reikaiBench", setOf(origin), world) { _, m, _, main, _ ->
            if (!main) return@addWebMessageListener
            val probe = runCatching { JSONObject(m.data ?: return@addWebMessageListener) }.getOrNull()
                ?: return@addWebMessageListener
            // Stands in for the interceptor's bridge, the only input a solve without its own probe has.
            probe.optString("cf").split('|').filter { it.isNotEmpty() }.forEach(solve::report)
            val line = "active=${probe.optString("active")} focus=${probe.optBoolean("focus")} " +
                "token=${probe.optInt("token")} cf=${probe.optString("cf")} phase=${solve.phase}"
            // Only changes, or a cf event, so a still page does not drown the press lines.
            if (line != last || probe.optString("cf").isNotEmpty()) log(line)
            last = line
        }
        WebViewCompat.addJavaScriptOnEvent(
            webView,
            PROBE,
            WebViewCompat.INJECTION_EVENT_DOCUMENT_START,
            setOf(origin),
            world,
        )

        load(webView)

        Handler(Looper.getMainLooper()).postDelayed(
            {
                solve.cancel()
                TurnstileSolver.detach(webView)
                webView.destroy()
                log("done, final phase ${solve.phase}")
            },
            RUN_MS,
        )
    }

    private fun log(line: String) = logcat(LogPriority.INFO) { line }
}
