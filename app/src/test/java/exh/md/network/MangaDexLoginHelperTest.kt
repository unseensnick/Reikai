package exh.md.network

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

class MangaDexLoginHelperTest {

    @Test
    fun `a login cancelled mid-request keeps the MangaDex sign-in`() = runTest {
        val mdList = mockk<MdList>(relaxed = true)
        // A call that never answers, so the login is still waiting on the token request when cancelled.
        val client = mockk<OkHttpClient> { every { newCall(any()) } returns mockk(relaxed = true) }
        val helper =
            MangaDexLoginHelper(client, Headers.headersOf(), mockk(relaxed = true), mdList, mockk(relaxed = true))

        val login = launch { helper.login("code") }
        runCurrent()
        login.cancelAndJoin()

        verify(exactly = 0) { mdList.logout() }
    }
}
