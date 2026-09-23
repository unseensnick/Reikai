package eu.kanade.tachiyomi.network.interceptor

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.Buffer
import java.util.Base64

/*
 * The rules of the WebView fetch fallback that need no WebView, kept apart so they are unit-testable.
 * A request reaches the page only as data (a JSON message the fixed script parses), never as script
 * text: an LN plugin chooses its method and headers, and splicing them into code would let it run
 * script in a site the user is signed in to. Record: docs/dev/plans/webview-fetch.md.
 */

private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")

// What a page may not set on a fetch, or what the WebView supplies itself (cookies, user agent).
private val DROPPED_HEADERS = setOf(
    "accept-charset", "accept-encoding", "connection", "content-length", "cookie", "cookie2", "date",
    "dnt", "expect", "host", "keep-alive", "origin", "referer", "te", "trailer", "transfer-encoding",
    "upgrade", "user-agent", "via",
)

/** The page's own origin for [url], which is also its key: a port or scheme apart is another page. */
internal fun webViewFetchOrigin(url: HttpUrl): String = with(url) {
    if (port == HttpUrl.defaultPort(scheme)) "$scheme://$host" else "$scheme://$host:$port"
}

internal fun isSameOrigin(a: HttpUrl, b: HttpUrl): Boolean = webViewFetchOrigin(a) == webViewFetchOrigin(b)

/**
 * The message that asks the page to fetch [request], or null when the WebView cannot carry it: a
 * method outside the fixed set, or a body OkHttp can only send once.
 */
internal fun webViewFetchMessage(id: String, request: Request, manualRedirect: Boolean): JsonObject? {
    val method = request.method.uppercase()
    if (method !in ALLOWED_METHODS) return null
    val body = request.body
    if (body != null && (body.isOneShot() || body.isDuplex())) return null
    return buildJsonObject {
        put("id", id)
        put("url", request.url.toString())
        put("method", method)
        put("redirect", if (manualRedirect) "manual" else "follow")
        // A page can only name a referrer on its own origin; the page sends none rather than its own.
        request.header("Referer")?.toHttpUrlOrNull()?.takeIf { isSameOrigin(it, request.url) }
            ?.let { put("referrer", it.toString()) }
        putJsonArray("headers") {
            request.headers
                .filter { (name, _) -> isForwardable(name) }
                .forEach { (name, value) ->
                    addJsonArray {
                        add(name)
                        add(value)
                    }
                }
        }
        if (body != null && method != "GET" && method != "HEAD") {
            val bytes = Buffer().also { body.writeTo(it) }.readByteArray()
            put("body", Base64.getEncoder().encodeToString(bytes))
            body.contentType()?.let { put("contentType", it.toString()) }
        }
    }
}

private fun isForwardable(header: String): Boolean = header.lowercase().let {
    it !in DROPPED_HEADERS && !it.startsWith("proxy-") && !it.startsWith("sec-")
}

/**
 * The page's answer as an OkHttp response, or null for a status no server sends (an opaque answer
 * reports 0). The body arrives already decoded, so the encoding and length the server sent would lie
 * about it. The final URL is only taken on the same origin: a redirect elsewhere is not followed here.
 */
internal fun webViewFetchResponse(
    request: Request,
    status: Int,
    statusText: String,
    headers: List<Pair<String, String>>,
    finalUrl: String?,
    body: Buffer,
): Response? {
    if (status !in 100..599) return null
    val responseHeaders = Headers.Builder().apply {
        headers.forEach { (name, value) ->
            if (!name.equals("content-encoding", true) && !name.equals("content-length", true)) {
                runCatching { add(name, value) }
            }
        }
    }.build()
    val landed = finalUrl?.toHttpUrlOrNull()?.takeIf { isSameOrigin(it, request.url) }
    return Response.Builder()
        .request(if (landed != null) request.newBuilder().url(landed).build() else request)
        .protocol(Protocol.HTTP_1_1)
        .code(status)
        .message(statusText)
        .headers(responseHeaders)
        .body(body.asResponseBody(responseHeaders["Content-Type"]?.toMediaTypeOrNull(), body.size))
        .build()
}

/**
 * The other site a followed fetch landed on, or null when it stayed on the request's own origin. The
 * browser hands back such an answer only when that site allows it, and it is taken as a redirect there,
 * so the body is never served under the original address.
 */
internal fun webViewFetchLandedElsewhere(request: Request, finalUrl: String?): String? =
    finalUrl?.toHttpUrlOrNull()?.takeUnless { isSameOrigin(it, request.url) }?.toString()

/** Whether a redirect of [request] may be followed at all: only a GET or HEAD, by a client that follows. */
internal fun followsRedirect(request: Request, followRedirects: Boolean): Boolean =
    followRedirects && request.method in setOf("GET", "HEAD")

/** Whether the page's answer is Cloudflare challenging the WebView too, which this cannot get past. */
internal fun isWebViewFetchChallenged(status: Int, headers: List<Pair<String, String>>): Boolean =
    status == 403 && headers.any { (name, value) -> name.equals("cf-mitigated", true) && value == "challenge" }

/**
 * The request that follows a redirect to [target], built the way OkHttp builds its own: http(s) only,
 * no scheme change unless the client allows it, and no credentials carried to another origin. Only a
 * GET or HEAD is followed; a redirected write is not replayed on another server's say-so.
 */
internal fun webViewFetchFollowUp(
    request: Request,
    target: String,
    followRedirects: Boolean,
    followSslRedirects: Boolean,
): Request? {
    if (!followsRedirect(request, followRedirects)) return null
    val url = request.url.resolve(target) ?: return null
    if (url.scheme != request.url.scheme && !followSslRedirects) return null
    return request.newBuilder().url(url).apply {
        if (!isSameOrigin(url, request.url)) {
            removeHeader("Authorization")
            removeHeader("Proxy-Authorization")
        }
    }.build()
}
