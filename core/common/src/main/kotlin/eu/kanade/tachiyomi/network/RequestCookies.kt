package eu.kanade.tachiyomi.network

import okhttp3.Cookie
import okhttp3.HttpUrl

/**
 * The cookies a request to [url] carries: the [jar]'s plus the ones in its own Cookie [header], the
 * header's winning by name. OkHttp on its own replaces the header with the jar's whenever the jar holds
 * any, which loses a plugin's age gate or read token; the LN host sends this set, and the bypass server
 * forwards the same one.
 */
fun requestCookies(jar: List<Cookie>, header: String?, url: HttpUrl): List<Cookie> {
    val own = header.orEmpty().split(';').mapNotNull { part ->
        val name = part.substringBefore('=').trim()
        if (name.isEmpty() || '=' !in part) return@mapNotNull null
        runCatching { Cookie.Builder().name(name).value(part.substringAfter('=').trim()).domain(url.host).build() }
            .getOrNull()
    }
    val names = own.mapTo(HashSet()) { it.name }
    return jar.filterNot { it.name in names } + own
}
