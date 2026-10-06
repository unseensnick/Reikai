package reikai.data.track

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.anilist.Anilist
import eu.kanade.tachiyomi.data.track.anilist.AnilistInterceptor
import eu.kanade.tachiyomi.data.track.anilist.dto.ALOAuth
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
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdates
import eu.kanade.tachiyomi.data.track.mangaupdates.MangaUpdatesInterceptor
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeList
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeListInterceptor
import eu.kanade.tachiyomi.data.track.myanimelist.dto.MALOAuth
import eu.kanade.tachiyomi.data.track.novellist.NovelListInterceptor
import eu.kanade.tachiyomi.data.track.ranobedb.RanobeDb
import eu.kanade.tachiyomi.data.track.ranobedb.RanobeDbInterceptor
import eu.kanade.tachiyomi.data.track.shikimori.Shikimori
import eu.kanade.tachiyomi.data.track.shikimori.ShikimoriInterceptor
import eu.kanade.tachiyomi.data.track.shikimori.dto.SMOAuth
import eu.kanade.tachiyomi.network.awaitSuccess
import exh.md.network.MangaDexAuthInterceptor
import io.kotest.matchers.shouldBe
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Dispatcher
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.presentation.track.TrackerError
import java.net.UnknownHostException
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

    @Test
    fun `an offline token refresh reads as offline`() = runTest {
        val interceptor = MyAnimeListInterceptor(
            mockk<MyAnimeList> {
                every { loadOAuth() } returns MALOAuth("r", "a", expiresIn = 0, createdAt = 0)
                every { getIfAuthExpired() } returns false
            },
        )
        TrackerError.of(call(interceptor, offline = true).second!!, isOnline = false) shouldBe TrackerError.Offline
    }

    /** What reached OkHttp's own thread uncaught, and what reached the caller. */
    private suspend fun call(interceptor: Interceptor, offline: Boolean = false): Pair<List<Throwable>, Throwable?> {
        val uncaught = CopyOnWriteArrayList<Throwable>()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable).apply { setUncaughtExceptionHandler { _, error -> uncaught += error } }
        }
        val client = OkHttpClient.Builder().dispatcher(Dispatcher(executor)).addInterceptor(interceptor)
            .apply { if (offline) dns { throw UnknownHostException(it) } }
            .build()
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
            SignedOutCase("NovelList") { NovelListInterceptor(null) },
            SignedOutCase("RanobeDB") {
                RanobeDbInterceptor(mockk<RanobeDb> { every { restoreToken() } returns null })
            },
            SignedOutCase("MangaUpdates") {
                MangaUpdatesInterceptor(mockk<MangaUpdates> { every { restoreSession() } returns null })
            },
            SignedOutCase("AniList, token with no stored login") {
                AnilistInterceptor(mockk<Anilist> { every { loadOAuth() } returns null }, "a")
            },
            SignedOutCase("AniList, expired login") {
                AnilistInterceptor(
                    mockk<Anilist> {
                        every { loadOAuth() } returns ALOAuth("a", expires = 0)
                        every { logout() } just Runs
                    },
                    "a",
                )
            },
            // The refresh token was refused, so the login is gone until the user signs in again.
            SignedOutCase("MyAnimeList, login expired") {
                MyAnimeListInterceptor(
                    mockk<MyAnimeList> {
                        every { loadOAuth() } returns null
                        every { getIfAuthExpired() } returns true
                    },
                )
            },
            SignedOutCase("MDList, unreadable stored login") {
                val mdList = mockk<MdList> {
                    every { id } returns 60L
                    every { name } returns "MDList"
                }
                val preferences = TrackPreferences(EmittingPreferenceStore())
                preferences.trackToken(mdList).set("not a login")
                MangaDexAuthInterceptor(preferences, mdList)
            },
        )
    }
}
