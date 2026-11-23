package reikai.data.track

import android.net.Uri
import android.os.SystemClock
import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.comick.ComickApi
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBakaApi
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeListApi
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALOAuth
import eu.kanade.tachiyomi.network.NetworkHelper
import exh.md.network.MangaDexAuthInterceptor
import exh.md.network.MangaDexLoginHelper
import exh.md.utils.MdConstants
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import mihon.app.di.AppBindings
import okhttp3.Dispatcher
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.presentation.recents.EmittingPreferenceStore
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Every tracker whose browser login uses PKCE, wired to the pending login in TrackerLoginPkce.kt: the
 * login URL stores the verifier, the redirect's exchange sends it, and a login that cannot complete keeps
 * the session. Nothing reaches a network: OkHttp runs each call inline, or holds it unanswered.
 */
class TrackerPkceLoginTest {

    enum class Service(val plainPkce: Boolean) {
        MDLIST(plainPkce = false) {
            override fun tracker(manager: TrackerManager) = manager.mdList
            override fun openLoginUrl(manager: TrackerManager, preferences: TrackPreferences) {
                MdConstants.Login.authUrl(preferences.newLoginPkce(manager.mdList).codeChallenge)
            }
            override suspend fun login(manager: TrackerManager, preferences: TrackPreferences, client: OkHttpClient) {
                val mdList = manager.mdList
                MangaDexLoginHelper(
                    client,
                    Headers.headersOf(),
                    preferences,
                    mdList,
                    MangaDexAuthInterceptor(preferences, mdList),
                ).login(CODE)
            }
        },
        MYANIMELIST(plainPkce = true) {
            override fun tracker(manager: TrackerManager) = manager.myAnimeList
            override fun openLoginUrl(manager: TrackerManager, preferences: TrackPreferences) {
                MyAnimeListApi.authUrl(preferences, manager.myAnimeList)
            }
            override suspend fun login(manager: TrackerManager, preferences: TrackPreferences, client: OkHttpClient) {
                manager.myAnimeList.login(CODE)
            }
        },
        COMICK(plainPkce = false) {
            override fun tracker(manager: TrackerManager) = manager.comick
            override fun openLoginUrl(manager: TrackerManager, preferences: TrackPreferences) {
                ComickApi.authUrl(preferences, manager.comick)
            }
            override suspend fun login(manager: TrackerManager, preferences: TrackPreferences, client: OkHttpClient) {
                manager.comick.login(CODE)
            }
            override fun verifyState(manager: TrackerManager, state: String) = manager.comick.verifyOAuthState(state)
        },
        MANGABAKA(plainPkce = false) {
            override fun tracker(manager: TrackerManager) = manager.mangaBaka
            override fun openLoginUrl(manager: TrackerManager, preferences: TrackPreferences) {
                MangaBakaApi.authUrl(preferences, manager.mangaBaka)
            }
            override suspend fun login(manager: TrackerManager, preferences: TrackPreferences, client: OkHttpClient) {
                manager.mangaBaka.login(CODE)
            }
            override fun verifyState(manager: TrackerManager, state: String) =
                manager.mangaBaka.verifyOAuthState(state)
        },
        ;

        /** The login activity's check of the redirect's OAuth state, for the trackers that send one. */
        open fun verifyState(manager: TrackerManager, state: String): Boolean? = null

        abstract fun tracker(manager: TrackerManager): Tracker
        abstract fun openLoginUrl(manager: TrackerManager, preferences: TrackPreferences)
        abstract suspend fun login(manager: TrackerManager, preferences: TrackPreferences, client: OkHttpClient)
    }

    private val store = EmittingPreferenceStore()
    private val sent = CopyOnWriteArrayList<Request>()
    private val enqueued = CompletableDeferred<Unit>()

    /** Answers every call at once: the token endpoint with a token, anything else as the account. */
    private val answering = client(
        object : InlineExecutor() {
            override fun execute(command: Runnable) = command.run()
        },
    )

    /** Never answers, so a login is still waiting on its code exchange when it is cancelled. */
    private val holding = client(
        object : InlineExecutor() {
            override fun execute(command: Runnable) {
                enqueued.complete(Unit)
            }
        },
    )

    private var queryParameters = linkedMapOf<String, String>()

    init {
        // The trackers' rate limiters read this clock, and the login URLs are android.net.Uri, which a plain
        // JVM test only has as stubs.
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 0L
        mockkStatic(Uri::class)
        val builder = mockk<Uri.Builder>()
        every { builder.appendQueryParameter(any(), any()) } answers {
            queryParameters[firstArg()] = secondArg()
            builder
        }
        every { builder.build() } returns mockk(relaxed = true)
        every { Uri.parse(any()) } returns mockk { every { buildUpon() } returns builder }
    }

    @AfterEach
    fun tearDown() {
        unmockkStatic(SystemClock::class, Uri::class)
    }

    /** A new app process over [client]: only what the preference store holds survives. */
    private fun process(client: OkHttpClient = answering): Pair<TrackerManager, TrackPreferences> {
        val network = mockk<NetworkHelper>(relaxed = true) { every { this@mockk.client } returns client }
        val dependencies = trackerTestDependencies(network, store)
        return trackerTestManager(dependencies) to dependencies.trackPreferences
    }

    @ParameterizedTest
    @EnumSource(Service::class)
    fun `the exchange sends the verifier the login URL stored before the process died`(service: Service) = runTest {
        val (before, beforePreferences) = process()
        service.openLoginUrl(before, beforePreferences)
        val stored = beforePreferences.trackPkceCodeVerifier(service.tracker(before)).get()
        val (after, afterPreferences) = process()

        service.login(after, afterPreferences, answering)

        sent.first().formFields()["code_verifier"] shouldBe stored
    }

    @ParameterizedTest
    @EnumSource(Service::class)
    fun `the login URL's challenge comes from the stored verifier`(service: Service) {
        val (manager, preferences) = process()

        service.openLoginUrl(manager, preferences)

        val verifier = preferences.trackPkceCodeVerifier(service.tracker(manager)).get()
        queryParameters["code_challenge"] shouldBe if (service.plainPkce) verifier else s256(verifier)
    }

    @ParameterizedTest
    @EnumSource(Service::class, names = ["COMICK", "MANGABAKA"])
    fun `the redirect's state from the login URL is accepted in a new process`(service: Service) {
        val (before, beforePreferences) = process()
        service.openLoginUrl(before, beforePreferences)
        val (after, _) = process()

        service.verifyState(after, queryParameters.getValue("state")) shouldBe true
    }

    @ParameterizedTest
    @EnumSource(Service::class)
    fun `with no pending login nothing is sent`(service: Service) = runTest {
        val (manager, preferences) = process()

        service.login(manager, preferences, answering)

        sent.size shouldBe 0
    }

    @ParameterizedTest
    @EnumSource(Service::class)
    fun `with no pending login the existing session is kept`(service: Service) = runTest {
        val (manager, preferences) = process()
        signedIn(preferences, service.tracker(manager))

        service.login(manager, preferences, answering)

        session(preferences, service.tracker(manager)) shouldBe SESSION
    }

    @ParameterizedTest
    @EnumSource(Service::class)
    fun `a cancelled code exchange keeps the existing session`(service: Service) = runTest {
        val (manager, preferences) = process(holding)
        signedIn(preferences, service.tracker(manager))
        service.openLoginUrl(manager, preferences)
        val login = launch { service.login(manager, preferences, holding) }
        enqueued.await()

        login.cancelAndJoin()

        session(preferences, service.tracker(manager)) shouldBe SESSION
    }

    private fun signedIn(preferences: TrackPreferences, tracker: Tracker) {
        preferences.setCredentials(tracker, SESSION.first, "old-access-token")
        preferences.trackToken(tracker).set(SESSION.second)
    }

    private fun session(preferences: TrackPreferences, tracker: Tracker) =
        preferences.trackUsername(tracker).get() to preferences.trackToken(tracker).get()

    private fun client(executor: AbstractExecutorService) = OkHttpClient.Builder()
        .dispatcher(Dispatcher(executor))
        .addInterceptor { chain ->
            val request = chain.request()
            sent += request
            val body = if (request.url.encodedPath.endsWith("/token")) TOKEN_BODY else USER_BODY
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        }
        .build()

    private fun Request.formFields(): Map<String, String> {
        val form = body as FormBody
        return (0 until form.size).associate { form.name(it) to form.value(it) }
    }

    private fun s256(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))

    private abstract class InlineExecutor : AbstractExecutorService() {
        override fun shutdown() = Unit
        override fun shutdownNow(): List<Runnable> = emptyList()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = true
    }
}

private const val CODE = "auth-code"

/** The username and stored token of a session the user already has. */
private val SESSION = "old-user" to AppBindings.providesJson().encodeToString(
    MALOAuth.serializer(),
    MALOAuth(refreshToken = "old-refresh-token", accessToken = "old-access-token", expiresIn = 3600),
)

/** One token response every tracker's DTO reads. */
private val TOKEN_BODY = """{
    "token_type": "Bearer",
    "access_token": "new-access-token",
    "refresh_token": "new-refresh-token",
    "expires_in": 3600,
    "expires_at": ${Long.MAX_VALUE / 2},
    "scope": "library:read library:write offline_access"
}"""

/** One account response every tracker's DTO reads. */
private const val USER_BODY = """{
    "name": "reader",
    "id": "1",
    "username": "reader",
    "data": {"id": "1", "rating_steps": 10, "nickname": "reader", "preferred_username": "reader"}
}"""
