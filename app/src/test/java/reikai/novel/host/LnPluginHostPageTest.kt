package reikai.novel.host

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import reikai.util.testAppScope

/**
 * A page that fails must reach the caller as a failure: the page walk counts an empty page as walked and
 * moves its mark past it. The JVM has no QuickJS, so a call to a plugin that is not loaded stands in for a
 * plugin whose parsePage rejects; both fail inside the host's call.
 */
class LnPluginHostPageTest {

    @Test
    fun `a page whose call fails throws rather than reading as an empty page`() = runTest {
        val host = LnPluginHost(
            testAppScope(),
            mockk<Context> { every { applicationContext } returns this },
            mockk<NetworkHelper> { every { client } returns OkHttpClient() },
            mockk(relaxed = true),
        )

        shouldThrow<LnPluginException> { host.parsePage("unloaded", "novel", "2") }
    }
}
