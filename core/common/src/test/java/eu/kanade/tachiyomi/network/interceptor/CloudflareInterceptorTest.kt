package eu.kanade.tachiyomi.network.interceptor

import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.network.AndroidCookieJar
import eu.kanade.tachiyomi.network.NetworkPreferences
import eu.kanade.tachiyomi.util.system.WebViewUtil
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkAll
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * [CloudflareInterceptor.intercept] driven through the real method, with the WebView half faked
 * by name, since it needs a device: the per-host lock, and the page a failed solve offers to open.
 */
class CloudflareInterceptorTest {

    @Volatile
    private var clearance: String? = null
    private val solves = AtomicInteger()
    private lateinit var interceptor: CloudflareInterceptor

    @BeforeEach
    fun setUp() {
        mockkStatic(ContextCompat::class)
        every { ContextCompat.getMainExecutor(any()) } returns Executor { it.run() }
        mockkStatic(Looper::class)
        every { Looper.getMainLooper() } returns mockk()
        mockkConstructor(Handler::class)
        mockkObject(WebViewUtil)
        every { WebViewUtil.supportsWebView(any()) } returns true

        val jar = mockk<AndroidCookieJar>()
        every { jar.get(any()) } answers {
            val url = firstArg<HttpUrl>()
            listOfNotNull(clearance?.let { Cookie.Builder().name("cf_clearance").value(it).domain(url.host).build() })
        }
        every { jar.remove(any(), any(), any()) } answers {
            clearance = null
            1
        }
        interceptor = spyk(
            CloudflareInterceptor(
                mockk(relaxed = true),
                jar,
                mockk(relaxed = true),
                { "ua" },
                mockk<NetworkPreferences>(relaxed = true) { every { flareSolverrUrl.get() } returns "" },
                // Not in use, so a failed solve falls to the WebView fetch rather than the server.
                mockk { every { isActive() } returns false },
            ),
            recordPrivateCalls = true,
        )
    }

    @AfterEach
    fun tearDown() = unmockkAll()

    private fun solveWith(block: () -> Unit) {
        every { interceptor["solve"](any<Interceptor.Chain>(), any<Request>(), any<Response>()) } answers {
            solves.incrementAndGet()
            block()
            null
        }
    }

    private fun chain(url: String, server: (Request) -> Response = ::server): Interceptor.Chain {
        val request = Request.Builder().url(url).build()
        return mockk {
            every { request() } returns request
            every { proceed(any()) } answers { server(firstArg()) }
            every { cookieJar } returns CookieJar.NO_COOKIES
        }
    }

    // Challenges every request until the host holds a clearance.
    private fun server(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .apply {
            if (clearance == null) {
                code(403).message("Forbidden").header("cf-mitigated", "challenge").header("Server", "cloudflare")
            } else {
                code(200).message("OK")
            }
        }
        .body("".toResponseBody())
        .build()

    private fun runBounded(block: () -> Response): Response? {
        val result = AtomicReference<Response?>()
        val worker = thread(isDaemon = true) { result.set(block()) }
        worker.join(TimeUnit.SECONDS.toMillis(5))
        return result.get()
    }

    @Test
    fun `a challenged request made from within another to the same host does not wait on itself`() {
        solveWith { clearance = "solved" }
        // The outer request is answered only after a nested one to the same host, as an extension's
        // token refresh is, and that nested one is challenged.
        val outer = chain("https://site.example/page") { request ->
            interceptor.intercept(chain("https://site.example/token")).close()
            server(request)
        }

        runBounded { interceptor.intercept(outer) }?.code shouldBe 200
    }

    @Test
    fun `concurrent challenges on one host are solved once`() {
        val queued = CountDownLatch(1)
        solveWith {
            queued.await(1, TimeUnit.SECONDS)
            clearance = "solved"
        }
        val start = CountDownLatch(1)
        val workers = List(8) {
            thread(isDaemon = true) {
                start.await()
                interceptor.intercept(chain("https://site.example/page/$it")).close()
            }
        }
        start.countDown()
        queued.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }

        solves.get() shouldBe 1
    }

    @Test
    fun `a failed solve releases the host for the next request`() {
        val failures = AtomicInteger()
        every { interceptor["solve"](any<Interceptor.Chain>(), any<Request>(), any<Response>()) } answers {
            solves.incrementAndGet()
            if (failures.getAndIncrement() == 0) throw IOException("bypass failed")
            clearance = "solved"
            null
        }
        runCatching { interceptor.intercept(chain("https://site.example/a")) }

        runBounded { interceptor.intercept(chain("https://site.example/b")) }?.code shouldBe 200
    }

    private fun blockedUrl(request: Request, respond: (Request) -> Response = ::server): String? {
        // Neither route answers: the WebView solve fails and the WebView fetch is challenged too.
        val fetcher = mockk<WebViewFetcher> {
            every { serves(any()) } returns false
            every { fetch(any(), any()) } returns WebViewFetcher.Outcome.Challenged
        }
        CloudflareInterceptor::class.java.getDeclaredField("webViewFetcher").apply { isAccessible = true }
            .set(interceptor, fetcher)
        val bypassFailure = Class.forName("eu.kanade.tachiyomi.network.interceptor.CloudflareBypassException")
            .getDeclaredConstructor(HttpUrl::class.java).apply { isAccessible = true }
        every { interceptor["resolveWithWebView"](any<Request>(), any<HttpUrl>(), any<String>()) } answers {
            throw bypassFailure.newInstance(null) as Throwable
        }
        val chain = mockk<Interceptor.Chain> {
            every { request() } returns request
            every { proceed(any()) } answers { respond(firstArg()) }
        }
        return runCatching { interceptor.intercept(chain) }.exceptionOrNull()?.cloudflareBlockedUrl()
    }

    @Test
    fun `a challenged POST that cannot be solved offers the site root to open`() {
        val search = Request.Builder().url("https://site.example/api/search?q=x").post("q=x".toRequestBody()).build()

        blockedUrl(search) shouldBe "https://site.example/"
    }

    /** Byparr would send it as a GET and that page would come back as the POST's answer. */
    @Test
    fun `a POST the server cannot carry stays with the WebView, even on a host the server solves`() {
        val server = mockk<FlareSolverrClient> {
            every { isActive() } returns true
            every { carries(any()) } returns false
            every { shouldSkipWebView(any()) } returns true
        }
        CloudflareInterceptor::class.java.getDeclaredField("flareSolverr").apply { isAccessible = true }
            .set(interceptor, server)
        val search = Request.Builder().url("https://site.example/api/search?q=x").post("q=x".toRequestBody()).build()

        blockedUrl(search) shouldBe "https://site.example/"
    }

    @Test
    fun `a POST redirected to a challenged GET offers the page the solve ran on`() {
        val login = Request.Builder().url("https://site.example/api/login").post("q=x".toRequestBody()).build()
        // What a 303 leaves: the challenge arrives on the GET the POST was sent on to.
        val landed = Request.Builder().url("https://site.example/account?tab=1").build()

        blockedUrl(login) { server(landed) } shouldBe "https://site.example/account?tab=1"
    }

    private fun setServer(server: FlareSolverrClient) {
        CloudflareInterceptor::class.java.getDeclaredField("flareSolverr").apply { isAccessible = true }
            .set(interceptor, server)
    }

    private fun ok(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("".toResponseBody())
        .build()

    // Every one of [count] requests is challenged before the solve finishes, so each has to queue.
    private fun allChallenged(count: Int, respond: (Request) -> Response): Pair<CountDownLatch, (Request) -> Response> {
        val challenged = CountDownLatch(count)
        return challenged to { request ->
            respond(request).also { if (it.code == 403) challenged.countDown() }
        }
    }

    private fun runAll(count: Int, call: (Int) -> Unit) {
        val start = CountDownLatch(1)
        val workers = List(count) {
            thread(isDaemon = true) {
                start.await()
                runCatching { call(it) }
            }
        }
        start.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }
    }

    @Test
    fun `requests to a host the server already answers are fetched side by side`() {
        val inside = CountDownLatch(2)
        val release = CountDownLatch(1)
        setServer(
            mockk {
                every { isActive() } returns true
                every { carries(any()) } returns true
                every { shouldSkipWebView(any()) } returns true
                every { resolve(any(), any(), any()) } answers {
                    inside.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    ok(secondArg())
                }
            },
        )
        val workers = List(2) { thread(isDaemon = true) { interceptor.intercept(chain("https://site.example/$it")) } }

        val bothInside = inside.await(2, TimeUnit.SECONDS)
        release.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(5)) }

        bothInside shouldBe true
    }

    @Test
    fun `requests queued behind a solve that left the host to the server are answered by it`() {
        val served = AtomicInteger()
        val marked = AtomicBoolean()
        setServer(
            mockk {
                every { isActive() } returns true
                every { carries(any()) } returns true
                every { shouldSkipWebView(any()) } answers { marked.get() }
                every { resolve(any(), any(), any()) } answers {
                    served.incrementAndGet()
                    ok(secondArg())
                }
            },
        )
        val (challenged, respond) = allChallenged(4, ::server)
        // The server's solve saves its cookies, a fresh cf_clearance among them, which a sibling would
        // otherwise take for a clearance it can replay.
        solveWith {
            challenged.await(1, TimeUnit.SECONDS)
            clearance = "from the server"
            marked.set(true)
        }

        runAll(4) { interceptor.intercept(chain("https://site.example/page/$it", respond)).close() }

        served.get() shouldBe 3
    }

    // A jar holding a clearance per host, so one site's solve leaves another's challenged.
    private fun clearancesPerHost(): ConcurrentHashMap<String, String> {
        val clearances = ConcurrentHashMap<String, String>()
        val jar = mockk<AndroidCookieJar>()
        every { jar.get(any()) } answers {
            val url = firstArg<HttpUrl>()
            listOfNotNull(
                clearances[url.host]?.let { Cookie.Builder().name("cf_clearance").value(it).domain(url.host).build() },
            )
        }
        every { jar.remove(any(), any(), any()) } answers {
            clearances.remove(firstArg<HttpUrl>().host)
            1
        }
        CloudflareInterceptor::class.java.getDeclaredField("cookieManager").apply { isAccessible = true }
            .set(interceptor, jar)
        return clearances
    }

    @Test
    fun `a challenge a WebView fetch was redirected into is solved once`() {
        val clearances = clearancesPerHost()
        val fetched = CountDownLatch(4)
        // The mirror's pages are fetched in the WebView, which lands on the main site, challenged until cleared.
        val fetcher = mockk<WebViewFetcher> {
            every { serves(any()) } returns true
            every { fetch(any(), any()) } answers {
                val landed = secondArg<Request>().newBuilder().url("https://main.example/").build()
                fetched.countDown()
                WebViewFetcher.Outcome.Served(if (clearances["main.example"] == null) server(landed) else ok(landed))
            }
        }
        CloudflareInterceptor::class.java.getDeclaredField("webViewFetcher").apply { isAccessible = true }
            .set(interceptor, fetcher)
        every { interceptor["resolveWithWebView"](any<Request>(), any<HttpUrl>(), any<String>()) } answers {
            solves.incrementAndGet()
            fetched.await(1, TimeUnit.SECONDS)
            clearances["main.example"] = "solved"
            Unit
        }
        val respond = { request: Request -> if (request.url.host == "main.example") ok(request) else server(request) }

        runAll(4) { interceptor.intercept(chain("https://mirror.example/page/$it", respond)).close() }

        solves.get() shouldBe 1
    }

    @Test
    fun `a challenge served after a redirect to another site is solved once`() {
        val clearances = clearancesPerHost()
        // The mirror redirects every request to the main site, which challenges until it is cleared.
        val (challenged, redirecting) = allChallenged(4) { request ->
            val landed = request.newBuilder().url("https://main.example${request.url.encodedPath}").build()
            if (clearances["main.example"] == null) server(landed) else ok(landed)
        }
        solveWith {
            challenged.await(1, TimeUnit.SECONDS)
            clearances["main.example"] = "solved"
        }

        runAll(4) { interceptor.intercept(chain("https://mirror.example/page/$it", redirecting)).close() }

        solves.get() shouldBe 1
    }

    @Test
    fun `a challenged GET that cannot be solved offers the page itself to open`() {
        val page = Request.Builder().url("https://site.example/series/1?page=2").build()

        blockedUrl(page) shouldBe "https://site.example/series/1?page=2"
    }
}
