package reikai.novel.source.ireader

import android.content.Context
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.HttpClientCall
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.engine.okhttp.OkHttpConfig
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.plugin
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
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.OkHttpClient
import reikai.novel.source.FetchFailures
import reikai.novel.source.httpFailure
import kotlin.coroutines.cancellation.CancellationException

/**
 * The HTTP half of what an IReader extension is built with. Both clients run over Reikai's own OkHttp
 * client, so FlareSolverr, the WebView fetch, the shared cookie jar and the cache apply as for any
 * other source, which is why IReader's own cookie and cache plugins are left out. Record:
 * docs/dev/subsystems/browse-and-sources.md, "The IReader runtime".
 */
class IReaderHttpClients(
    private val context: Context,
    okHttp: OkHttpClient,
    private val userAgent: () -> String,
) : HttpClientsInterface {

    // Lazy, like everything below built on it: its constructor loads the WebView provider, which blocks
    // for seconds on a cold device, and most extensions never touch it.
    private val webViewCookieJar by lazy { WebViewCookieJar(AcceptAllCookiesStorage()) }

    // First, so what it asks in a source's place still passes the app's Cloudflare and agent handling.
    private val sourceClient = okHttp.newBuilder().apply { interceptors().add(0, MadaraChapterEndpoint) }.build()

    // IReader's own client decodes JSON bodies with Gson, so an extension's plain classes need it.
    override val default: HttpClient = client(sourceClient) { install(ContentNegotiation) { gson() } }

    override val cloudflareClient: HttpClient = client(sourceClient) {}

    // IReader's own engine: extensions call it for pages that need a browser, and the class is final.
    override val browser: BrowserEngine by lazy { BrowserEngine(WebViewManger(context), webViewCookieJar) }

    override val config: NetworkConfig by lazy { NetworkConfig(userAgent = userAgent()) }

    override val sslConfig: SSLConfiguration = SSLConfiguration()

    override val cookieSynchronizer: CookieSynchronizer by lazy { CookieSynchronizer(webViewCookieJar) }

    // Ktor names itself "ktor-client" when a request names no agent, and the app's interceptor leaves a
    // named one alone, so the app's agent is set here, per request, never over an extension's own.
    private fun client(okHttp: OkHttpClient, configure: HttpClientConfig<OkHttpConfig>.() -> Unit) =
        HttpClient(OkHttp) {
            engine {
                preconfigured = okHttp
                // Ktor's engine turns OkHttp's redirects off and follows only GET and HEAD itself, so a
                // search form that answers its POST with a 302 came back empty. OkHttp follows it as a
                // browser does, as a GET, as it does for every manga source on this client.
                config {
                    followRedirects(true)
                    followSslRedirects(true)
                }
            }
            defaultRequest { headers.appendIfNameAbsent(HttpHeaders.UserAgent, userAgent()) }
            configure()
        }.apply { plugin(HttpSend).intercept { request -> recordingFailure { execute(request) } } }

    // Ktor hands an extension an error page as an answer, so the source call is told of it (FetchFailures).
    // The send runs in the extension's own coroutine, which carries the call's element.
    private suspend fun recordingFailure(send: suspend () -> HttpClientCall): HttpClientCall {
        val failures = currentCoroutineContext()[FetchFailures]
        val call = try {
            send()
        } catch (e: Exception) {
            if (e !is CancellationException) failures?.record(e)
            throw e
        }
        httpFailure(call.response.status.value)?.let { failures?.record(it) }
        return call
    }
}
