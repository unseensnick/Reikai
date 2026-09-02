package eu.kanade.tachiyomi.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

class AndroidCookieJar : CookieJar {

    private val manager = CookieManager.getInstance()

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
            // RK: expire every scope the cookie could be stored under, not just the host at the
            //     request's own directory. The store keys on (name, domain, path), so a bare
            //     "name=" removes nothing when the cookie was set on a parent domain or at "/",
            //     which is how Cloudflare sets cf_clearance.
            .onEach { name -> scopesOf(url.host).forEach { manager.setCookie(urlString, expiry(name, maxAge, it)) } }
            .count()
    }

    // RK -->
    fun saveCookieString(url: HttpUrl, cookieString: String) = manager.setCookie(url.toString(), cookieString)

    /** The host itself, then every dotted parent short of the public suffix. */
    private fun scopesOf(host: String): List<String> {
        val labels = host.split('.')
        return listOf("") + (0..labels.size - 2).map { "; Domain=.${labels.drop(it).joinToString(".")}" }
    }

    private fun expiry(name: String, maxAge: Int, domain: String) = "$name=; Max-Age=$maxAge; Path=/$domain"
    // RK <--

    fun removeAll() {
        manager.removeAllCookies {}
    }
}
