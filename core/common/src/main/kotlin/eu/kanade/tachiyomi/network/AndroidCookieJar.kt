package eu.kanade.tachiyomi.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class AndroidCookieJar : CookieJar {

    private val manager by lazy { CookieManager.getInstance() }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val urlString = url.toString()

        cookies.forEach { manager.setCookie(urlString, it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        return get(url)
    }

    fun get(url: HttpUrl): List<Cookie> {
        val cookies = manager.getCookie(url.toString())

        return if (cookies != null && cookies.isNotEmpty()) {
            cookies.split(";").mapNotNull { Cookie.parse(url, it) }
        } else {
            emptyList()
        }
    }

    fun remove(url: HttpUrl, cookieNames: List<String>? = null, maxAge: Int = -1): Int {
        val urlString = url.toString()
        val cookies = manager.getCookie(urlString) ?: return 0

        fun List<String>.filterNames(): List<String> {
            return if (cookieNames != null) {
                this.filter { it in cookieNames }
            } else {
                this
            }
        }

        return cookies.split(";")
            // RK: trim so non-first cookies (" b=2") match the name filter
            .map { it.substringBefore("=").trim() }
            .filterNames()
            // RK: expire every scope the cookie could be stored under: each parent domain and each
            //     directory of the path. The store keys on (name, domain, path), so a bare "name="
            //     removes nothing when the cookie was set on a parent domain or at "/", which is how
            //     Cloudflare sets cf_clearance.
            .onEach { name ->
                pathsOf(url).forEach { path ->
                    scopesOf(url).forEach { manager.setCookie(urlString, expiry(name, maxAge, path, it)) }
                }
            }
            .count()
    }

    // RK -->
    fun saveCookieString(url: HttpUrl, cookieString: String) = manager.setCookie(url.toString(), cookieString)

    /**
     * The host itself, then every dotted parent short of the top-level label. A parent that is a longer
     * public suffix (co.uk) is written too and refused by the store, which rejects such a Domain; OkHttp's
     * suffix list would need its Android initializer, which no JVM test of this jar has. Over https each
     * scope is expired twice, since the store keeps a Partitioned copy apart and only an expiry carrying
     * the attribute reaches it; Cloudflare sets cf_clearance that way on some sites.
     */
    private fun scopesOf(url: HttpUrl): List<String> {
        val labels = url.host.split('.')
        val domains = listOf("") + (0..labels.size - 2).map { "; Domain=.${labels.drop(it).joinToString(".")}" }
        return if (url.isHttps) domains.flatMap { listOf(it, "$it; Secure; SameSite=None; Partitioned") } else domains
    }

    // "/" and every directory of the request's path: a cookie set with no Path is stored under its page's own.
    private fun pathsOf(url: HttpUrl): List<String> {
        val segments = url.encodedPathSegments.filter { it.isNotEmpty() }
        return (0..segments.size).map { "/" + segments.take(it).joinToString("/") }
    }

    private fun expiry(name: String, maxAge: Int, path: String, domain: String) =
        "$name=; Max-Age=$maxAge; Path=$path$domain"
    // RK <--

    fun removeAll() {
        manager.removeAllCookies {}
    }
}
