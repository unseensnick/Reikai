package reikai.novel.network

import android.content.Context
import android.webkit.WebSettings
import okhttp3.Request

/**
 * The request headers a light-novel network call carries when the caller, not a plugin, builds the
 * request. Used by [reikai.novel.host.LnHostBridge] and the novel cover fetcher, so per-source header
 * handling lives in one place. Mihon's shared network client otherwise injects a stripped, generic
 * "Android 10; K" User-Agent, which some LN hosts answer with a degraded page or a thumbnail-only
 * cover; LNReader sends the device's real WebView UA, mirrored here. Cookies ride the shared OkHttp
 * `cookieJar`, the same jar FlareSolverr populates, and need no handling here.
 */

@Volatile
private var cachedDeviceUserAgent: String? = null

/** The device WebView User-Agent (real model + Android + Chrome version), cached per process; empty
 *  string on failure so callers can treat it as "unavailable, keep the client default". */
fun deviceWebViewUserAgent(context: Context): String {
    cachedDeviceUserAgent?.let { return it }
    return runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("").also {
        cachedDeviceUserAgent = it
    }
}

/**
 * Apply the LN default request headers: the device [deviceUserAgent] (unless [pluginSetUserAgent], so
 * a plugin-set UA wins) and, for cover loads, the source site as a [referer]. Each is set only when a
 * value is actually available; an explicit caller/plugin value is never overwritten.
 */
fun Request.Builder.applyNovelDefaults(
    deviceUserAgent: String,
    referer: String? = null,
    pluginSetUserAgent: Boolean = false,
): Request.Builder = apply {
    if (deviceUserAgent.isNotBlank() && !pluginSetUserAgent) header("User-Agent", deviceUserAgent)
    if (!referer.isNullOrBlank()) header("Referer", referer)
}
