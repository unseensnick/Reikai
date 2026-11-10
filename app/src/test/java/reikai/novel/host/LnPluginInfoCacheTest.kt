package reikai.novel.host

import android.content.Context
import eu.kanade.tachiyomi.network.NetworkHelper
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import reikai.presentation.recents.EmittingPreferenceStore
import reikai.util.testAppScope
import java.io.File

/** A cold start reads a plugin's description back only while nothing that read saw has changed. */
class LnPluginInfoCacheTest {

    @TempDir
    lateinit var dir: File

    private val info = LnPluginInfo(id = "plugin", name = "Plugin", site = "https://site.test")

    private fun key(runtime: String = "runtime", storage: String = "site=https://site.test") =
        LnPluginInfoCache.key(runtime, "script", storage)

    @Test
    fun `an unchanged plugin is read back`() {
        LnPluginInfoCache(dir).put("plugin", key(), info)

        LnPluginInfoCache(dir).get("plugin", key()) shouldBe info
    }

    @Test
    fun `a changed setting reads the plugin again`() {
        LnPluginInfoCache(dir).put("plugin", key(), info)

        LnPluginInfoCache(dir).get("plugin", key(storage = "site=https://other.test")) shouldBe null
    }

    @Test
    fun `a changed runtime reads the plugin again`() {
        LnPluginInfoCache(dir).put("plugin", key(), info)

        LnPluginInfoCache(dir).get("plugin", key(runtime = "runtime 2")) shouldBe null
    }

    /** The JVM has no QuickJS, so an uncached read fails here: an answer means no engine was asked. */
    @Test
    fun `an installed plugin read before is described without an engine`() = runTest {
        val context = mockk<Context> {
            every { applicationContext } returns this
            every { filesDir } returns dir
            every { assets.open(any()) } answers { "".byteInputStream() }
        }
        val host = LnPluginHost(
            testAppScope(),
            context,
            mockk<NetworkHelper> { every { client } returns OkHttpClient() },
            EmittingPreferenceStore(),
        )
        LnPluginInfoCache(File(dir, "lnplugin-info")).put("plugin", host.infoKey("script", "plugin"), info)

        // Real time: the read's timeout runs on the test clock, which skips ahead while the cache reads disk.
        withContext(Dispatchers.Default) { host.inspectPlugin("script", expectedId = "plugin") }.site shouldBe
            "https://site.test"
    }
}
