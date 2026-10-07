package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.AndroidCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl

/** The cookie a passed Cloudflare challenge leaves, which a retry rides on. */
internal const val CF_CLEARANCE = "cf_clearance"

internal fun AndroidCookieJar.clearanceFor(url: HttpUrl): Cookie? = get(url).firstOrNull { it.name == CF_CLEARANCE }
