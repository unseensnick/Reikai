package exh.md.network

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.mdlist.MdList
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Headers
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import reikai.data.track.newLoginPkce
import reikai.presentation.recents.EmittingPreferenceStore

class MangaDexLoginHelperTest {

    @Test
    fun `a login cancelled mid-request keeps the MangaDex sign-in`() = runTest {
        val mdList = mockk<MdList>(relaxed = true) {
            every { id } returns 60L
            every { name } returns "MDList"
        }
        val preferences = TrackPreferences(EmittingPreferenceStore())
        // A pending login, so the exchange is sent rather than refused for want of a verifier.
        preferences.newLoginPkce(mdList)
        // A call that never answers, so the login is still waiting on the token request when cancelled.
        val client = mockk<OkHttpClient> { every { newCall(any()) } returns mockk(relaxed = true) }
        val helper = MangaDexLoginHelper(client, Headers.headersOf(), preferences, mdList, mockk(relaxed = true))

        val login = launch { helper.login("code") }
        runCurrent()
        login.cancelAndJoin()

        verify(exactly = 0) { mdList.logout() }
    }
}
