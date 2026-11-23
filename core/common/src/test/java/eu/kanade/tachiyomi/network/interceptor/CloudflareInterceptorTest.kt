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
 * by name, since it needs a device: the per-host lock, and the page a failed solve offers to open. Real
 * threads stay: the interceptor is synchronous and its per-host lock blocks, which no virtual clock reaches.
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
                mockk { every { handles(any()) } returns false },
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
    private fun server(request: Request): Response = if (clearance == null) challenge(request) else ok(request)

    private fun challenge(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(403)
        .message("Forbidden")
        .header("cf-mitigated", "challenge")
        .header("Server", "cloudflare")
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
        val respond = allChallenged(8, ::server)
        solveWith { clearance = "solved" }

        runAll(8) { interceptor.intercept(chain("https://site.example/page/$it", respond)).close() }

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

    // Every WebView solve gives up, as one that times out does.
    private fun failWebViewSolve() {
        val bypassFailure = Class.forName("eu.kanade.tachiyomi.network.interceptor.CloudflareBypassException")
            .getDeclaredConstructor(HttpUrl::class.java).apply { isAccessible = true }
        every { interceptor["resolveWithWebView"](any<Request>(), any<HttpUrl>(), any<String>()) } answers {
            throw bypassFailure.newInstance(null) as Throwable
        }
    }

    private fun blockedUrl(
        request: Request,
        // Neither route answers by default: the WebView solve fails and the WebView fetch is challenged too.
        fetcher: WebViewFetcher = mockk {
            every { serves(any()) } returns false
            every { fetch(any(), any()) } returns WebViewFetcher.Outcome.Challenged
        },
        respond: (Request) -> Response = ::server,
    ): String? {
        CloudflareInterceptor::class.java.getDeclaredField("webViewFetcher").apply { isAccessible = true }
            .set(interceptor, fetcher)
        failWebViewSolve()
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
            every { handles(any()) } returns false
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

    // OkHttp drops Authorization on a redirect to another host; the WebView must not put it back.
    @Test
    fun `a solve after a redirect to another host loads without the first host's sign-in`() {
        val signedIn = Request.Builder().url("https://api.a.example/feed").header("Authorization", "Bearer t").build()
        val landed = Request.Builder().url("https://b.example/feed").build()
        val loaded = AtomicReference<Request>()
        every { interceptor["resolveWithWebView"](any<Request>(), any<HttpUrl>(), any<String>()) } answers {
            loaded.set(firstArg())
            clearance = "solved"
        }
        val chain = mockk<Interceptor.Chain> {
            every { request() } returns signedIn
            every { proceed(any()) } answers { if (clearance == null) challenge(landed) else ok(firstArg()) }
            every { cookieJar } returns CookieJar.NO_COOKIES
        }

        runBounded { interceptor.intercept(chain) }

        loaded.get()?.header("Authorization") shouldBe null
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

    // Every one of [count] requests is challenged before any solve starts, so each has to pass the
    // dedup check. They meet inside their first response, while the host's read lock still lets them
    // all in; a solve waiting for them instead holds the write lock that keeps them out.
    private fun allChallenged(count: Int, respond: (Request) -> Response): (Request) -> Response {
        val arrived = CountDownLatch(count)
        return { request ->
            arrived.countDown()
            arrived.await(1, TimeUnit.SECONDS)
            respond(request)
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
                every { handles(any()) } returns true
                every { shouldSkipWebView(any()) } returns true
                every { resolve(any(), any()) } answers {
                    inside.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    ok(firstArg())
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
                every { handles(any()) } returns true
                every { shouldSkipWebView(any()) } answers { marked.get() }
                every { resolve(any(), any()) } answers {
                    served.incrementAndGet()
                    ok(firstArg())
                }
            },
        )
        // A host on Cloudflare's stricter tier challenges a replayed clearance, so it keeps challenging after the
        // server's solve. That solve saves its cookies, a fresh cf_clearance among them, which a sibling would
        // otherwise take for a clearance it can replay.
        val respond = allChallenged(4, ::challenge)
        solveWith {
            clearance = "from the server"
            marked.set(true)
        }

        runAll(4) { interceptor.intercept(chain("https://site.example/page/$it", respond)).close() }

        served.get() shouldBe 3
    }

    @Test
    fun `a host a sibling leaves to the server while the request is being routed is answered by the server`() {
        val served = AtomicInteger()
        setServer(
            mockk {
                every { handles(any()) } returns true
                // The sibling's solve marks the host between the request's first look and its next.
                every { shouldSkipWebView(any()) } returnsMany listOf(false, true)
                every { resolve(any(), any()) } answers {
                    served.incrementAndGet()
                    ok(firstArg())
                }
            },
        )
        CloudflareInterceptor::class.java.getDeclaredField("webViewFetcher").apply { isAccessible = true }
            .set(
                interceptor,
                mockk<WebViewFetcher> {
                    every { serves(any()) } returns false
                    every { fetch(any(), any()) } answers { WebViewFetcher.Outcome.Served(ok(secondArg())) }
                },
            )

        runBounded { interceptor.intercept(chain("https://site.example/page", ::challenge)) }

        served.get() shouldBe 1
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
    fun `a WebView fetch challenged before a sibling's solve and returning after it is not solved again`() {
        val clearances = clearancesPerHost()
        val started = CountDownLatch(2)
        val solved = CountDownLatch(1)
        val calls = AtomicInteger()
        // Both fetches land on the main site before it is cleared; the second returns only once the first
        // request's solve has finished, as a slow thread does.
        val fetcher = mockk<WebViewFetcher> {
            every { serves(any()) } returns true
            every { fetch(any(), any()) } answers {
                val late = calls.incrementAndGet() == 2
                val landed = secondArg<Request>().newBuilder().url("https://main.example/").build()
                val response = if (clearances["main.example"] == null) server(landed) else ok(landed)
                started.countDown()
                if (late) solved.await(5, TimeUnit.SECONDS)
                WebViewFetcher.Outcome.Served(response)
            }
        }
        CloudflareInterceptor::class.java.getDeclaredField("webViewFetcher").apply { isAccessible = true }
            .set(interceptor, fetcher)
        every { interceptor["resolveWithWebView"](any<Request>(), any<HttpUrl>(), any<String>()) } answers {
            solves.incrementAndGet()
            started.await(5, TimeUnit.SECONDS)
            clearances["main.example"] = "solved"
            solved.countDown()
            Unit
        }
        val respond = { request: Request -> if (request.url.host == "main.example") ok(request) else server(request) }

        runAll(2) { interceptor.intercept(chain("https://mirror.example/page/$it", respond)).close() }

        solves.get() shouldBe 1
    }

    @Test
    fun `a WebView fetch challenged before a direct solve of its landing site is not solved again`() {
        val clearances = clearancesPerHost()
        val started = CountDownLatch(1)
        val solved = CountDownLatch(1)
        // Only the mirror is fetched in a WebView; the main site is asked for directly and solved there, while
        // the mirror's fetch, which landed on the main site before that, is still out.
        val fetcher = mockk<WebViewFetcher> {
            every { serves(any()) } answers { firstArg<Request>().url.host == "mirror.example" }
            every { fetch(any(), any()) } answers {
                val landed = secondArg<Request>().newBuilder().url("https://main.example/").build()
                val response = if (clearances["main.example"] == null) server(landed) else ok(landed)
                started.countDown()
                solved.await(5, TimeUnit.SECONDS)
                WebViewFetcher.Outcome.Served(response)
            }
        }
        CloudflareInterceptor::class.java.getDeclaredField("webViewFetcher").apply { isAccessible = true }
            .set(interceptor, fetcher)
        every { interceptor["resolveWithWebView"](any<Request>(), any<HttpUrl>(), any<String>()) } answers {
            solves.incrementAndGet()
            started.await(5, TimeUnit.SECONDS)
            clearances["main.example"] = "solved"
            solved.countDown()
            Unit
        }
        val respond = { request: Request ->
            if (request.url.host == "main.example" &&
                clearances["main.example"] != null
            ) {
                ok(request)
            } else {
                server(request)
            }
        }

        runAll(2) {
            val url = if (it == 0) "https://main.example/page" else "https://mirror.example/page"
            interceptor.intercept(chain(url, respond)).close()
        }

        solves.get() shouldBe 1
    }

    @Test
    fun `a challenge served after a redirect to another site is solved once`() {
        val clearances = clearancesPerHost()
        // The mirror redirects every request to the main site, which challenges until it is cleared.
        val redirecting = allChallenged(4) { request ->
            val landed = request.newBuilder().url("https://main.example${request.url.encodedPath}").build()
            if (clearances["main.example"] == null) server(landed) else ok(landed)
        }
        solveWith { clearances["main.example"] = "solved" }

        runAll(4) { interceptor.intercept(chain("https://mirror.example/page/$it", redirecting)).close() }

        solves.get() shouldBe 1
    }

    @Test
    fun `a challenge two sites redirect to is solved once`() {
        val clearances = clearancesPerHost()
        val arrived = CountDownLatch(2)
        val solved = CountDownLatch(1)
        // Two mirrors, each its own host lock, redirect to the main site, which challenges until cleared. Both
        // are challenged before the solve, and the second returns only after it, so it reads the fresh clearance
        // as the one it was challenged under, outside any lock the solve held.
        val redirecting = { request: Request ->
            val landed = request.newBuilder().url("https://main.example${request.url.encodedPath}").build()
            val response = if (clearances["main.example"] == null) server(landed) else ok(landed)
            arrived.countDown()
            arrived.await(5, TimeUnit.SECONDS)
            if (request.url.host == "mirror-1.example") solved.await(5, TimeUnit.SECONDS)
            response
        }
        // The WebView alone is faked: the real solve numbers itself (markSolved), which is what tells the late
        // mirror the clearance it read is a sibling's.
        every { interceptor["resolveWithWebView"](any<Request>(), any<HttpUrl>(), any<String>()) } answers {
            solves.incrementAndGet()
            clearances["main.example"] = "solved"
            solved.countDown()
            Unit
        }

        runAll(2) { interceptor.intercept(chain("https://mirror-$it.example/page", redirecting)).close() }

        solves.get() shouldBe 1
    }

    @Test
    fun `a request redirected into a challenge a direct solve then clears is not solved again`() {
        val clearances = clearancesPerHost()
        val solved = CountDownLatch(1)
        // The mirror's request lands on the main site challenged, and only returns once a direct request to the
        // main site has solved it, so the clearance it then reads is the fresh one.
        val respond = { request: Request ->
            when {
                request.url.host == "mirror.example" -> {
                    val landed = request.newBuilder().url("https://main.example/").build()
                    if (solved.count == 0L) {
                        ok(landed)
                    } else {
                        solved.await(5, TimeUnit.SECONDS)
                        challenge(landed)
                    }
                }
                clearances["main.example"] != null -> ok(request)
                else -> server(request)
            }
        }
        every { interceptor["resolveWithWebView"](any<Request>(), any<HttpUrl>(), any<String>()) } answers {
            solves.incrementAndGet()
            clearances["main.example"] = "solved"
            solved.countDown()
            Unit
        }

        runAll(2) {
            val url = if (it == 0) "https://main.example/page" else "https://mirror.example/page"
            interceptor.intercept(chain(url, respond)).close()
        }

        solves.get() shouldBe 1
    }

    @Test
    fun `a challenged GET that cannot be solved offers the page itself to open`() {
        val page = Request.Builder().url("https://site.example/series/1?page=2").build()

        blockedUrl(page) shouldBe "https://site.example/series/1?page=2"
    }

    // The root a redirect lands on is often not challenged at all, so the page asked for is the one to open.
    @Test
    fun `a GET redirected into a challenge that cannot be solved offers the page asked for`() {
        val page = Request.Builder().url("https://site.example/series/1").build()
        val landed = Request.Builder().url("https://site.example/").build()

        blockedUrl(page) { server(landed) } shouldBe "https://site.example/series/1"
    }

    @Test
    fun `a WebView fetch sent to a challenge that cannot be solved offers the challenged page`() {
        val fetcher = mockk<WebViewFetcher> {
            every { serves(any()) } returns true
            every { fetch(any(), any()) } answers {
                WebViewFetcher.Outcome.Served(
                    challenge(secondArg<Request>().newBuilder().url("https://main.example/").build()),
                )
            }
            every { markServed(any()) } returns true
        }

        blockedUrl(Request.Builder().url("https://mirror.example/page").build(), fetcher = fetcher) shouldBe
            "https://main.example/"
    }

    @Test
    fun `a failed WebView solve hands the request to the server and leaves the host to it`() {
        val marked = mutableListOf<String>()
        setServer(
            mockk {
                every { handles(any()) } returns true
                every { shouldSkipWebView(any()) } returns false
                every { markWebViewUnsolvable(any()) } answers { marked += firstArg<String>() }
                every { resolve(any(), any()) } answers { ok(firstArg()) }
            },
        )
        failWebViewSolve()

        val code = runBounded { interceptor.intercept(chain("https://site.example/page")) }?.code

        (code to marked) shouldBe (200 to listOf("site.example"))
    }
}
