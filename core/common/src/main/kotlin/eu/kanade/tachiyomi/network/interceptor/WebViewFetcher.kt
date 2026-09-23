package eu.kanade.tachiyomi.network.interceptor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import eu.kanade.tachiyomi.util.system.setDefaultSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import logcat.LogPriority
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import tachiyomi.core.common.util.system.logcat
import java.io.IOException
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Answers a request from inside a WebView, for a site whose Cloudflare lets the WebView through but
 * challenges OkHttp and never issues a clearance the app could reuse. Each origin gets a detached
 * WebView holding a blank page on that origin, so no site script runs beside the fixed fetch script,
 * and requests reach it only as JSON over an origin-scoped message channel. Rules that need no
 * WebView live in WebViewFetch.kt; the design and its security review are in
 * docs/dev/plans/webview-fetch.md.
 */
class WebViewFetcher(private val context: Context) {

    sealed interface Outcome {
        data class Served(val response: Response) : Outcome
        data object Challenged : Outcome
        data class Failed(val error: IOException) : Outcome
    }

    private val main = Handler(Looper.getMainLooper())
    private val json = Json { ignoreUnknownKeys = true }

    // Origins where this route has worked, so a later challenge skips the solve that cannot succeed.
    private val servedOrigins = LinkedHashSet<String>()

    // Touched on the main thread only. Access-ordered, so the eldest is the least recently used.
    private val pages = LinkedHashMap<String, Page>(MAX_PAGES, 0.75f, true)

    fun serves(request: Request): Boolean = synchronized(servedOrigins) {
        webViewFetchOrigin(request.url) in servedOrigins
    }

    fun forget(request: Request) {
        synchronized(servedOrigins) { servedOrigins.remove(webViewFetchOrigin(request.url)) }
    }

    /** Fetches [request] through the WebView. Blocks the calling thread, which must not be the main one. */
    fun fetch(chain: Interceptor.Chain, request: Request): Outcome {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            return Outcome.Failed(IOException("WebView fetch on the main thread"))
        }
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            return Outcome.Failed(IOException("WebView too old for a fetch"))
        }
        var current = request
        repeat(MAX_HOPS) {
            // Credentials, or a client that follows nothing, never ride a redirect the browser follows:
            // only the rules below may decide one.
            val manualOnly = current.header("Authorization") != null || !chain.followRedirects
            val first = fetchOnce(chain, current, manualRedirect = manualOnly)
            // A fetch that followed a redirect to another site is refused unless that site allows it,
            // so ask again without following, and learn the target from a navigation instead.
            val redirected = when {
                first is Answer.Redirect -> true
                manualOnly || first !is Answer.Error || !first.fromPage -> false
                current.method !in setOf("GET", "HEAD") -> false
                else -> fetchOnce(chain, current, manualRedirect = true) is Answer.Redirect
            }
            if (!redirected) return first.toOutcome(current)
            // Checked before the navigation below, which would load the page only for its answer to be refused.
            if (!followsRedirect(
                    current,
                    chain.followRedirects,
                )
            ) {
                return Outcome.Failed(IOException("Redirect not followed"))
            }
            val target = (first as? Answer.Redirect)?.target ?: redirectTarget(current)
                ?: return Outcome.Failed(IOException("Redirect target not found"))
            val next = webViewFetchFollowUp(current, target, chain.followRedirects, chain.followSslRedirects)
                ?: return Outcome.Failed(IOException("Redirect not followed"))
            if (!isSameOrigin(next.url, current.url)) {
                // From the request this interceptor was handed, so the interceptors after it add their
                // own headers again, and undo their own encodings, as on OkHttp's own redirect.
                // A 303 already turned the chain's POST into this GET.
                val base = chain.request()
                    .let { if (it.method == current.method) it else it.newBuilder().get().build() }
                val hop = webViewFetchFollowUp(base, next.url.toString(), true, chain.followSslRedirects)
                    ?: return Outcome.Failed(IOException("Redirect not followed"))
                return Outcome.Served(chain.proceed(hop))
            }
            current = next
        }
        return Outcome.Failed(IOException("Too many redirects"))
    }

    fun markServed(request: Request) = synchronized(servedOrigins) {
        if (servedOrigins.size >= MAX_SERVED_ORIGINS) servedOrigins.remove(servedOrigins.first())
        servedOrigins.add(webViewFetchOrigin(request.url))
    }

    private sealed interface Answer {
        data class Done(val response: Response?, val challenged: Boolean) : Answer

        /** [target] is known when the fetch followed it to another site and that site allowed the answer. */
        data class Redirect(val target: String? = null) : Answer

        // fromPage: the fetch itself failed, which is what a redirect to another site looks like.
        data class Error(val message: String, val fromPage: Boolean = false) : Answer
    }

    private fun Answer.toOutcome(request: Request): Outcome = when (this) {
        is Answer.Done -> when {
            challenged -> Outcome.Challenged
            response == null -> Outcome.Failed(IOException("WebView fetch returned no status"))
            else -> Outcome.Served(response)
        }
        is Answer.Redirect -> Outcome.Failed(IOException("Unexpected redirect"))
        is Answer.Error -> Outcome.Failed(IOException("WebView fetch of ${request.url.host} failed: $message"))
    }

    private fun fetchOnce(chain: Interceptor.Chain, request: Request, manualRedirect: Boolean): Answer {
        val id = UUID.randomUUID().toString()
        val message = webViewFetchMessage(id, request, manualRedirect)
            ?: return Answer.Error("request not carried")
        val origin = webViewFetchOrigin(request.url)
        val userAgent = request.header("User-Agent")
        val page = AtomicReference<Page?>()
        val opened = CountDownLatch(1)
        main.post {
            try {
                page.set(openPage(origin, userAgent))
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, e) { "WebView fetch: no WebView for ${request.url.host}" }
            } finally {
                opened.countDown()
            }
        }
        if (!opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) return Answer.Error("page not created")
        val open = page.get() ?: return Answer.Error("page not created")
        val proxy = open.awaitReady() ?: return Answer.Error("page not ready")
        val inbox = LinkedBlockingQueue<String>()
        open.inboxes[id] = inbox
        if (open.dead) inbox.offer(PAGE_CLOSED)
        main.post { if (!open.dead) proxy.postMessage(message.toString()) }
        try {
            return collect(chain, request, id, inbox)
        } catch (e: IOException) {
            main.post { if (!open.dead) proxy.postMessage(buildJsonObject { put("abort", id) }.toString()) }
            return Answer.Error(e.message ?: "aborted")
        } finally {
            open.inboxes.remove(id)
            main.post { scheduleTeardown(origin) }
        }
    }

    private fun collect(
        chain: Interceptor.Chain,
        request: Request,
        id: String,
        inbox: LinkedBlockingQueue<String>,
    ): Answer {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FETCH_TIMEOUT_SECONDS)
        var head: JsonObject? = null
        val body = Buffer()
        while (true) {
            if (chain.call().isCanceled()) throw IOException("Canceled")
            if (System.nanoTime() > deadline) throw IOException("WebView fetch timed out")
            val raw = inbox.poll(POLL_MILLIS, TimeUnit.MILLISECONDS) ?: continue
            if (raw == PAGE_CLOSED) throw IOException("WebView page closed")
            val next = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
            if (next["id"]?.jsonPrimitive?.content != id) continue
            when (next["kind"]?.jsonPrimitive?.content) {
                "redirect" -> return Answer.Redirect()
                "error" -> {
                    val reason = next["message"]?.jsonPrimitive?.content?.take(64) ?: "error"
                    return Answer.Error(reason, fromPage = true)
                }
                "head" -> head = next.also {
                    val length = it.headerPairs().firstOrNull { (n, _) -> n.equals("content-length", true) }
                    if ((length?.second?.toLongOrNull() ?: 0L) > MAX_BODY_BYTES) throw IOException("Response too large")
                }
                "chunk" -> {
                    body.write(Base64.getDecoder().decode(next["data"]?.jsonPrimitive?.content.orEmpty()))
                    if (body.size > MAX_BODY_BYTES) throw IOException("Response too large")
                }
                "end" -> {
                    val done = head ?: return Answer.Error("no status")
                    val status = done["status"]?.jsonPrimitive?.int ?: 0
                    val headers = done.headerPairs()
                    if (isWebViewFetchChallenged(status, headers)) return Answer.Done(null, challenged = true)
                    webViewFetchLandedElsewhere(request, done["url"]?.jsonPrimitive?.content)
                        ?.let { return Answer.Redirect(target = it) }
                    val response = webViewFetchResponse(
                        request = request,
                        status = status,
                        statusText = done["statusText"]?.jsonPrimitive?.content.orEmpty(),
                        headers = headers,
                        finalUrl = done["url"]?.jsonPrimitive?.content,
                        body = body,
                    )
                    return Answer.Done(response, challenged = false)
                }
            }
        }
    }

    private fun JsonObject.headerPairs(): List<Pair<String, String>> =
        this["headers"]?.jsonArray.orEmpty().mapNotNull { pair ->
            val parts = runCatching { pair.jsonArray }.getOrNull() ?: return@mapNotNull null
            if (parts.size != 2) return@mapNotNull null
            parts[0].jsonPrimitive.content to parts[1].jsonPrimitive.content
        }

    // Main thread.
    @SuppressLint("SetJavaScriptEnabled")
    private fun openPage(origin: String, userAgent: String?): Page? {
        pages[origin]?.takeUnless { it.dead }?.let { page ->
            main.removeCallbacksAndMessages(page)
            if (userAgent != null && page.webView.settings.userAgentString != userAgent) {
                page.webView.settings.userAgentString = userAgent
            }
            return page
        }
        pages.remove(origin)?.destroy()
        // Only an idle page is evicted: a busy one would fail its fetch. All busy, the cap stretches.
        while (pages.size >= MAX_PAGES) {
            val idle = pages.entries.firstOrNull { !it.value.busy } ?: break
            pages.remove(idle.key)?.destroy()
        }
        val webView = WebView(context).apply {
            setDefaultSettings()
            if (!userAgent.isNullOrBlank()) settings.userAgentString = userAgent
        }
        val page = Page(origin, webView)
        return try {
            WebViewCompat.addWebMessageListener(webView, BRIDGE, setOf(origin)) { _, message, source, isMain, proxy ->
                if (!isMain || source != Uri.parse(origin) || page.dead) return@addWebMessageListener
                val data = message.data ?: return@addWebMessageListener
                // Only routed here, by the id each message leads with, and parsed by the waiting thread:
                // chunks are large and this is the main thread.
                if (data == READY) {
                    page.ready.compareAndSet(null, proxy)
                    page.readyLatch.countDown()
                } else if (data.startsWith(ID_PREFIX)) {
                    page.inboxes[data.substring(ID_PREFIX.length).substringBefore('"')]?.offer(data)
                }
            }
            webView.webViewClient = page.client
            webView.loadDataWithBaseURL("$origin/", FETCH_PAGE, "text/html", "utf-8", null)
            pages[origin] = page
            page
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, e) { "WebView fetch: could not open a page for ${Uri.parse(origin).host}" }
            webView.destroy()
            null
        }
    }

    // Main thread.
    private fun scheduleTeardown(origin: String) {
        val page = pages[origin] ?: return
        main.removeCallbacksAndMessages(page)
        val at = SystemClock.uptimeMillis() + IDLE_MILLIS
        main.postAtTime({ if (!page.busy && pages[origin] === page) pages.remove(origin)?.destroy() }, page, at)
    }

    /**
     * Where a redirect points, learned from a navigation that stops at the first hop, so the target
     * never loads. JavaScript stays on because Cloudflare challenges a browser without it; the page
     * that runs is the site's own, as in the solve, and this WebView carries no bridge. A challenge
     * may reload its own origin once it passes; any other navigation ends the wait.
     */
    private fun redirectTarget(request: Request): String? {
        val origin = webViewFetchOrigin(request.url)
        val path = request.url.encodedPath
        val target = AtomicReference<String?>()
        val done = CountDownLatch(1)
        val view = AtomicReference<WebView?>()
        main.post {
            val webView = WebView(context).apply {
                setDefaultSettings()
                request.header("User-Agent")?.takeIf { it.isNotBlank() }?.let { settings.userAgentString = it }
                webViewClient = object : WebViewClient() {
                    var challenged = false

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        if (!request.isForMainFrame) return true
                        if (request.isRedirect) {
                            target.compareAndSet(null, request.url.toString())
                            done.countDown()
                            return true
                        }
                        // Only the challenge reloading the page asked for, so no other redirect is taken.
                        val reload = request.url.toString().toHttpUrlOrNull()
                            ?.let { webViewFetchOrigin(it) == origin && it.encodedPath == path } == true
                        if (!reload) done.countDown()
                        return !reload
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                        challenged = false
                    }

                    override fun onReceivedHttpError(
                        view: WebView,
                        request: WebResourceRequest,
                        errorResponse: WebResourceResponse,
                    ) {
                        if (request.isForMainFrame) {
                            challenged = errorResponse.responseHeaders["cf-mitigated"] == "challenge"
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        if (!challenged) done.countDown()
                    }

                    override fun onRenderProcessGone(
                        view: WebView?,
                        detail: RenderProcessGoneDetail?,
                    ): Boolean {
                        done.countDown()
                        return true
                    }
                }
            }
            view.set(webView)
            webView.loadUrl(request.url.toString())
        }
        try {
            done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } finally {
            main.post {
                view.get()?.run {
                    stopLoading()
                    destroy()
                }
            }
        }
        return target.get()
    }

    private inner class Page(val origin: String, val webView: WebView) {
        val ready = AtomicReference<JavaScriptReplyProxy?>()
        val readyLatch = CountDownLatch(1)
        val inboxes = ConcurrentHashMap<String, LinkedBlockingQueue<String>>()
        val busy: Boolean get() = inboxes.isNotEmpty()

        @Volatile var dead = false
        private var starts = 0

        // Only the blank page may ever load: a navigation would put a site document, or anything a
        // service worker sends it to, behind the channel, so the page is dropped instead.
        val client = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                if (++starts > 1) pages.remove(origin)?.destroy()
            }

            override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                pages.remove(origin)?.destroy()
                return true
            }
        }

        fun awaitReady(): JavaScriptReplyProxy? =
            if (readyLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS) && !dead) ready.get() else null

        // Main thread.
        fun destroy() {
            if (dead) return
            dead = true
            readyLatch.countDown()
            inboxes.values.forEach { it.offer(PAGE_CLOSED) }
            main.removeCallbacksAndMessages(this)
            webView.stopLoading()
            webView.destroy()
        }
    }

    private companion object {
        const val BRIDGE = "reikaiFetch"
        const val READY = "{\"kind\":\"ready\"}"
        const val PAGE_CLOSED = "closed"
        const val ID_PREFIX = "{\"id\":\""
        const val MAX_PAGES = 4
        const val MAX_SERVED_ORIGINS = 64
        const val MAX_HOPS = 5
        const val MAX_BODY_BYTES = 32L * 1024 * 1024
        const val TIMEOUT_SECONDS = 20L
        const val FETCH_TIMEOUT_SECONDS = 90L
        const val POLL_MILLIS = 250L
        const val IDLE_MILLIS = 60_000L

        // Fixed: nothing from a request is ever part of this text. Chunks stay small because the
        // listener runs on the main thread.
        val FETCH_PAGE = """
            <!doctype html><html><head><script>
            (() => {
              const bridge = window.$BRIDGE;
              if (!bridge) return;
              const send = (m) => bridge.postMessage(JSON.stringify(m));
              const aborts = {};
              const toBase64 = (bytes) => {
                let s = '';
                for (let i = 0; i < bytes.length; i += 8192) {
                  s += String.fromCharCode.apply(null, bytes.subarray(i, i + 8192));
                }
                return btoa(s);
              };
              const fromBase64 = (text) => Uint8Array.from(atob(text), (c) => c.charCodeAt(0));
              bridge.onmessage = async (event) => {
                let req;
                try { req = JSON.parse(event.data); } catch (e) { return; }
                if (req.abort) { aborts[req.abort]?.abort(); return; }
                const id = String(req.id);
                const controller = new AbortController();
                aborts[id] = controller;
                try {
                  const headers = new Headers();
                  for (const [name, value] of req.headers || []) { try { headers.append(name, value); } catch (e) {} }
                  if (req.contentType && !headers.has('content-type')) headers.set('content-type', req.contentType);
                  const init = { method: req.method, headers, credentials: 'include', cache: 'no-store',
                    redirect: req.redirect === 'manual' ? 'manual' : 'follow', signal: controller.signal };
                  if (req.referrer) init.referrer = req.referrer; else init.referrerPolicy = 'no-referrer';
                  if (req.body != null) init.body = fromBase64(req.body);
                  const res = await fetch(req.url, init);
                  if (res.type === 'opaqueredirect') { send({ id, kind: 'redirect' }); return; }
                  const list = [];
                  res.headers.forEach((value, name) => list.push([name, value]));
                  send({ id, kind: 'head', status: res.status, statusText: res.statusText, url: res.url,
                    headers: list });
                  if (res.body) {
                    const reader = res.body.getReader();
                    for (;;) {
                      const { done, value } = await reader.read();
                      if (done) break;
                      for (let i = 0; i < value.length; i += 196608) {
                        send({ id, kind: 'chunk', data: toBase64(value.subarray(i, i + 196608)) });
                      }
                    }
                  }
                  send({ id, kind: 'end' });
                } catch (e) {
                  send({ id, kind: 'error', message: String(e && e.name) });
                } finally {
                  delete aborts[id];
                }
              };
              send({ kind: 'ready' });
            })();
            </script></head><body></body></html>
        """.trimIndent()
    }
}
