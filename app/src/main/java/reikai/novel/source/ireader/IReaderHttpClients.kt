package reikai.novel.source.ireader

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpHeaders
import io.ktor.serialization.gson.gson
import io.ktor.util.appendIfNameAbsent
import ireader.core.http.BrowserEngine
import ireader.core.http.CookieSynchronizer
import ireader.core.http.HttpClientsInterface
import ireader.core.http.NetworkConfig
import ireader.core.http.SSLConfiguration
import ireader.core.http.WebViewCookieJar
import ireader.core.http.WebViewManger
import okhttp3.OkHttpClient

/**
 * The HTTP half of what an IReader extension is built with. Both clients run over Reikai's own OkHttp
 * client, so FlareSolverr, the WebView fetch, the shared cookie jar and the cache apply as for any
 * other source, which is why IReader's own cookie and cache plugins are left out. Record:
 * docs/dev/plans/content-layer-sources-surface.md, "The IReader runtime".
 */
class IReaderHttpClients(
    private val context: Context,
    okHttp: OkHttpClient,
    private val userAgent: () -> String,
) : HttpClientsInterface {

    // Lazy, like everything below built on it: its constructor loads the WebView provider, which blocks
    // for seconds on a cold device, and most extensions never touch it.
    private val webViewCookieJar by lazy { WebViewCookieJar(AcceptAllCookiesStorage()) }

    // IReader's own client decodes JSON bodies with Gson, so an extension's plain classes need it.
    override val default: HttpClient = client(okHttp) { install(ContentNegotiation) { gson() } }

    override val cloudflareClient: HttpClient = client(okHttp) {}

    // IReader's own engine: extensions call it for pages that need a browser, and the class is final.
    override val browser: BrowserEngine by lazy { BrowserEngine(WebViewManger(context), webViewCookieJar) }

    override val config: NetworkConfig by lazy { NetworkConfig(userAgent = userAgent()) }

    override val sslConfig: SSLConfiguration = SSLConfiguration()

    override val cookieSynchronizer: CookieSynchronizer by lazy { CookieSynchronizer(webViewCookieJar) }

    // Ktor names itself "ktor-client" when a request names no agent, and the app's interceptor leaves a
    // named one alone, so the app's agent is set here, per request, never over an extension's own.
    private fun client(okHttp: OkHttpClient, configure: HttpClientConfig<OkHttpConfig>.() -> Unit) =
        HttpClient(OkHttp) {
            engine { preconfigured = okHttp }
            defaultRequest { headers.appendIfNameAbsent(HttpHeaders.UserAgent, userAgent()) }
            configure()
        }
}
