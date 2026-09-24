package reikai.novel.host

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.collections.shouldContainOnly
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test

/**
 * A plugin load reaches the engine from whatever thread the caller is on, often a screen's Main.
 * The host owns the move onto the slot's own thread, so no caller has to remember it. The JVM has
 * no QuickJS native library, so the engine itself fails here; the vendor asset read that precedes
 * it is what shows which thread the engine work ran on.
 */
class LnPluginHostThreadTest {

    @Test
    fun `a plugin load reads the runtime on the loader slot's own thread`() = runTest {
        val readOn = mutableListOf<String>()
        val context = mockk<Context> {
            every { applicationContext } returns this
            every { assets.open(any()) } answers {
                // Coroutine debug mode appends " @coroutine#N" to the thread name.
                readOn += Thread.currentThread().name.substringBefore(" @")
                "".byteInputStream()
            }
        }
        val host = LnPluginHost(
            context,
            mockk<NetworkHelper> { every { client } returns OkHttpClient() },
            mockk(relaxed = true),
        )

        // Real time: the test scheduler skips ahead while this waits on the slot's thread, which fires
        // the load's timeout before the runtime is read.
        runCatching { withContext(Dispatchers.Default) { host.loadPlugin("plugin", "source") } }

        readOn.distinct() shouldContainOnly listOf("LnPlugin-loader")
    }
}
