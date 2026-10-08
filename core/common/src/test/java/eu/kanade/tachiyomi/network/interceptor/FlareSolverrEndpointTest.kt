package eu.kanade.tachiyomi.network.interceptor

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** The test, the session setup and every solve post to one address. */
class FlareSolverrEndpointTest {

    @Test
    fun `an address with or without a trailing slash posts to the same endpoint`() {
        listOf("http://10.0.0.2:8191", "http://10.0.0.2:8191/").map(::flareSolverrEndpoint) shouldBe
            listOf("http://10.0.0.2:8191/v1", "http://10.0.0.2:8191/v1")
    }
}
