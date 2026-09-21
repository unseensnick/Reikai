package reikai.novel.source

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginInfo

/** An LNReader plugin's settings live in the host's per-plugin storage, keyed by the plugin's own id. */
class LnPluginSourceSettingsTest {

    private val host = mockk<LnPluginHost> {
        every { getSetting("p", "domain") } returns JsonPrimitive("mirror")
        justRun { setSetting(any(), any(), any()) }
    }

    private fun settings(schema: JsonObject?) =
        LnPluginSource(host, LnPluginInfo(id = "p", name = "P", pluginSettings = schema)).settings

    private val schema = JsonObject(mapOf("domain" to JsonObject(emptyMap())))

    @Test
    fun `a plugin without settings declares none`() {
        settings(null) shouldBe null
    }

    @Test
    fun `a read comes from the plugin's own storage`() = runTest {
        (settings(schema) as NovelSettings.LnSchema).get("domain") shouldBe JsonPrimitive("mirror")
    }

    @Test
    fun `a write lands in the plugin's own storage`() {
        (settings(schema) as NovelSettings.LnSchema).set("domain", JsonPrimitive("main"))

        verify { host.setSetting("p", "domain", JsonPrimitive("main")) }
    }
}
