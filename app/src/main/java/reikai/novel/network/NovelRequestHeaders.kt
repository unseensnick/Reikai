package reikai.novel.network

import android.content.Context
import android.webkit.WebSettings

/**
 * The request headers a light-novel network call carries when the caller, not a plugin, builds the
 * request, for a plugin's own fetches ([reikai.novel.host.LnHostBridge]) and its images
 * ([NovelImageRequests]). Mihon's shared network client otherwise injects a stripped, generic
 * "Android 10; K" User-Agent, which some LN hosts answer with a degraded page or a thumbnail-only
 * cover; LNReader sends the device's real WebView UA, mirrored here. Cookies ride the shared OkHttp
 * `cookieJar`, the same jar FlareSolverr populates, and need no handling here.
 */

@Volatile
private var cachedDeviceUserAgent: String? = null

/**
 * The device WebView User-Agent (real model + Android + Chrome version), cached per process once
 * known; an empty string when the lookup fails, which callers read as "keep the client default" and
 * which is asked again next time, since a WebView mid-update fails a lookup it later answers.
 */
fun deviceWebViewUserAgent(context: Context): String {
    cachedDeviceUserAgent?.let { return it }
    val agent = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrNull()
    return agent?.takeIf { it.isNotBlank() }?.also { cachedDeviceUserAgent = it }.orEmpty()
}
