package reikai.novel.source

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import reikai.novel.host.LnPluginHost
import reikai.novel.host.LnPluginInfo

/** What an LNReader plugin receives when a listing is paged, since its filters and Latest share one options object. */
class LnPluginSourceBrowseTest {

    private val options = slot<String>()
    private val host = mockk<LnPluginHost> {
        coEvery { popularNovels(any(), any(), capture(options)) } returns emptyList()
    }

    private fun source(filters: JsonObject?) = LnPluginSource(
        host,
        LnPluginInfo(id = "p", name = "P", filters = filters),
    )

    private fun sentOptions() = Json.parseToJsonElement(options.captured).jsonObject

    @Test
    fun `Latest reaches a plugin that declares no filters`() = runTest {
        source(filters = null).browse(NovelListing.Latest, page = 1, filters = null)

        sentOptions()["showLatestNovels"]!!.jsonPrimitive.boolean shouldBe true
    }

    @Test
    fun `the reader's pick reaches the plugin in place of the default`() = runTest {
        val schema = JsonObject(mapOf("sort" to JsonObject(mapOf("value" to JsonPrimitive("new")))))

        source(schema).browse(
            NovelListing.Popular,
            page = 1,
            filters = NovelFilterState.LnValues(mapOf("sort" to JsonPrimitive("rating"))),
        )

        sentOptions()["filters"]!!.jsonObject["sort"]!!.jsonObject["value"] shouldBe JsonPrimitive("rating")
    }

    @Test
    fun `no pick sends the plugin's own default`() = runTest {
        val schema = JsonObject(mapOf("sort" to JsonObject(mapOf("value" to JsonPrimitive("new")))))

        source(schema).browse(NovelListing.Popular, page = 1, filters = null)

        sentOptions()["filters"]!!.jsonObject["sort"]!!.jsonObject["value"] shouldBe JsonPrimitive("new")
    }

    @Test
    fun `an empty schema declares no filters`() {
        source(JsonObject(emptyMap())).filters shouldBe null
    }
}
