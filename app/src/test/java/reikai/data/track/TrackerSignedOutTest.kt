package reikai.data.track

import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.anilist.AnilistInterceptor
import eu.kanade.tachiyomi.data.track.bangumi.Bangumi
import eu.kanade.tachiyomi.data.track.bangumi.BangumiInterceptor
import eu.kanade.tachiyomi.data.track.bangumi.dto.BGMOAuth
import eu.kanade.tachiyomi.data.track.hikka.Hikka
import eu.kanade.tachiyomi.data.track.hikka.HikkaInterceptor
import eu.kanade.tachiyomi.data.track.kitsu.Kitsu
import eu.kanade.tachiyomi.data.track.kitsu.KitsuInterceptor
import eu.kanade.tachiyomi.data.track.kitsu.dto.KitsuOAuth
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBaka
import eu.kanade.tachiyomi.data.track.mangabaka.MangaBakaInterceptor
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeListInterceptor
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriInterceptor
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMOAuth
import eu.kanade.tachiyomi.network.awaitSuccess
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.presentation.track.TrackerError
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One tracker's interceptor in a state with no usable login. */
class SignedOutCase(private val label: String, val interceptor: () -> Interceptor) {
    override fun toString() = label
}

/**
 * A tracker call made with no usable login, through OkHttp's enqueue path (awaitSuccess), which every
 * tracker API but the Apollo ones uses. OkHttp hands an IOException from an interceptor to the caller;
 * anything else it also rethrows on its own dispatcher thread, which crashes the app.
 */
class TrackerSignedOutTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a call with no login fails without throwing on the network thread`(case: SignedOutCase) = runTest {
        call(case.interceptor()).first shouldBe emptyList()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a call with no login reads as signed out`(case: SignedOutCase) = runTest {
        TrackerError.of(call(case.interceptor()).second!!, isOnline = true) shouldBe TrackerError.SignedOut
    }

    /** What reached OkHttp's own thread uncaught, and what reached the caller. */
    private suspend fun call(interceptor: Interceptor): Pair<List<Throwable>, Throwable?> {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable).apply { setUncaughtExceptionHandler { _, error -> uncaught += error } }
        }
        val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).addInterceptor(interceptor).build()
        val caught = runCatching {
            client.newCall(Request.Builder().url("http://127.0.0.1:9/").build()).awaitSuccess()
        }.exceptionOrNull()
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
        return uncaught.toList() to caught
    }

    companion object {
        @JvmStatic
        fun cases() = listOf(
            SignedOutCase("Bangumi") { BangumiInterceptor(mockk<Bangumi> { every { restoreToken() } returns null }) },
            SignedOutCase("MangaBaka") {
                MangaBakaInterceptor(mockk<MangaBaka> { every { restoreToken() } returns null })
            },
            SignedOutCase("Hikka") { HikkaInterceptor(mockk<Hikka> { every { loadOAuth() } returns null }) },
            SignedOutCase("AniList") { AnilistInterceptor(mockk<Anilist>(), null) },
            SignedOutCase("Kitsu") { KitsuInterceptor(mockk<Kitsu> { every { restoreToken() } returns null }) },
            SignedOutCase("Shikimori") {
                ShikimoriInterceptor(mockk<Shikimori> { every { restoreToken() } returns null })
            },
            SignedOutCase("MyAnimeList") {
                MyAnimeListInterceptor(
                    mockk<MyAnimeList> {
                        every { loadOAuth() } returns null
                        every { getIfAuthExpired() } returns false
                    },
                )
            },
            // A stored login whose refresh token is missing, which the refresh used to force with `!!`.
            SignedOutCase("Kitsu, no refresh token") {
                KitsuInterceptor(mockk<Kitsu> { every { restoreToken() } returns KitsuOAuth("a", 0, 0, null) })
            },
            SignedOutCase("Bangumi, expired with no refresh token") {
                BangumiInterceptor(
                    mockk<Bangumi> {
                        every { restoreToken() } returns
                            BGMOAuth("a", createdAt = 0, expiresIn = 0, refreshToken = null)
                    },
                )
            },
            SignedOutCase("Shikimori, expired with no refresh token") {
                ShikimoriInterceptor(mockk<Shikimori> { every { restoreToken() } returns SMOAuth("a", 0, 0, null) })
            },
        )
    }
}
