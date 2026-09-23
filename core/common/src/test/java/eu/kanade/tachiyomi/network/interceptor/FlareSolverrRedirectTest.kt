package eu.kanade.tachiyomi.network.interceptor

import eu.kanade.tachiyomi.network.NetworkPreferences
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class FlareSolverrRedirectTest {

    /** Following one drops the login, so a wrong address would read as a wrong password. */
    @Test
    fun `the solver's client never follows a redirect`() {
        val client = FlareSolverrClient(mockk(relaxed = true), NetworkPreferences(InMemoryPreferenceStore(), false))

        client.flareSolverrClient.followRedirects shouldBe false
    }
}
