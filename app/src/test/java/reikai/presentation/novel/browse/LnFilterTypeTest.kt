package reikai.presentation.novel.browse

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import reikai.novel.source.buildOptions
import reikai.novel.source.defaultFilterValues
import java.io.File

/** The filter types a plugin can declare, from the host's `FilterTypes` to the options its listing receives. */
class LnFilterTypeTest {

    private val shimTypes = Regex("""var FilterTypes = Object\.freeze\(\{(.*?)\}\);""", RegexOption.DOT_MATCHES_ALL)
        .find(File("src/main/assets/lnhost/headless.js").readText())!!
        .groupValues[1]
        .let { block -> Regex(""":\s*"([^"]+)"""").findAll(block).map { it.groupValues[1] }.toList() }

    private val schema = Json.parseToJsonElement(
        """{"tags":{"type":"MultiText","label":"Tags","value":["isekai"]}}""",
    ).jsonObject

    @Test
    fun `every type the host hands a plugin is one the sheet draws`() {
        shimTypes.filter { LnFilterType.of(it) == null } shouldBe emptyList()
    }

    @Test
    fun `the host hands a plugin the MultiText type`() {
        LnFilterType.of(shimTypes.single { it == "MultiText" }) shouldBe LnFilterType.MultiText
    }

    @Test
    fun `a type this version does not know reads as unsupported`() {
        LnFilterType.of("Slider") shouldBe null
    }

    @Test
    fun `a MultiText filter defaults to the plugin's declared values`() {
        checkedValues(defaultFilterValues(schema)["tags"]) shouldContainExactly listOf("isekai")
    }

    @Test
    fun `an edited MultiText filter reaches the plugin as its string array`() {
        val edited = multiTextValue(checkedValues(defaultFilterValues(schema)["tags"]).toList() + "magic school")

        sentValue(mapOf("tags" to edited)) shouldBe """["isekai","magic school"]"""
    }

    @Test
    fun `a MultiText entry is split at line breaks and trimmed, blanks and repeats dropped`() {
        multiTextValue(listOf("isekai", " isekai ", "", "magic\n school ")).toString() shouldBe
            """["isekai","magic","school"]"""
    }

    private fun sentValue(values: Map<String, JsonElement>): String =
        (Json.parseToJsonElement(buildOptions(schema, values, showLatest = false)).jsonObject["filters"] as JsonObject)
            .getValue("tags").jsonObject.getValue("value").toString()
}
