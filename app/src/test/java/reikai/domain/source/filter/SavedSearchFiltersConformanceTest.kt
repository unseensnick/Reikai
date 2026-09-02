package reikai.domain.source.filter

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * The rule both content types owe a saved search: what the user picked comes back, an unreadable
 * payload is ignored rather than thrown, and a filter added since the save leaves the rest applied.
 * Pinned once over both probes instead of as a twin pair.
 *
 * Where they genuinely differ, under a filter removed or reordered, each declares its own outcome
 * rather than the case being dropped. Background: docs/dev/plans/browse-feed-tab.md.
 */
class SavedSearchFiltersConformanceTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `what the user picked comes back`(probe: SavedSearchFiltersProbe) {
        // Deliberately the first of three, not the middle: a middle pick survives an encoding that
        // reverses the list, so it cannot tell a working match from a mirrored one.
        val saved = probe.save(names = listOf("A", "B", "C"), chosen = "A")!!

        probe.restore(saved, names = listOf("A", "B", "C")) shouldBe listOf("A")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a filter added since the save leaves the rest applied`(probe: SavedSearchFiltersProbe) {
        val saved = probe.save(names = listOf("A", "B", "C"), chosen = "A")!!

        probe.restore(saved, names = listOf("A", "B", "C", "D")) shouldBe listOf("A")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `an unreadable payload leaves the source's own filters alone`(probe: SavedSearchFiltersProbe) {
        probe.restore("not json at all", names = listOf("A", "B", "C"), preset = "A") shouldBe listOf("A")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a source with no filters saves nothing`(probe: SavedSearchFiltersProbe) {
        probe.save(names = emptyList(), chosen = null).shouldBeNull()
    }

    @Test
    fun `a manga filter removed since the save shifts the saved value onto its neighbour`() {
        // The cost of matching by position, inherited from upstream and bounded rather than fixed:
        // C comes back on though the user picked B. Filter names are not unique enough to key on.
        val probe = MangaSavedSearchFiltersProbe()
        val saved = probe.save(names = listOf("A", "B", "C"), chosen = "B")!!

        probe.restore(saved, names = listOf("A", "C")) shouldBe listOf("C")
    }

    @Test
    fun `a novel filter removed since the save leaves the others alone`() {
        val probe = NovelSavedSearchFiltersProbe()
        val saved = probe.save(names = listOf("A", "B", "C"), chosen = "B")!!

        probe.restore(saved, names = listOf("A", "C")).shouldBeEmpty()
    }

    @Test
    fun `one unreadable filter in a saved manga search costs only that filter`() {
        // What the per-element catch buys: a source that changed one filter's kind loses that filter's
        // saved value, not the whole search. Without it the first bad element aborts the rest.
        val probe = MangaSavedSearchFiltersProbe()
        val saved = probe.save(names = listOf("A", "B", "C"), chosen = "B")!!
        val corrupted = JsonArray(
            Json.parseToJsonElement(saved).jsonArray.mapIndexed { index, element ->
                if (index == 0) {
                    JsonObject(element.jsonObject + (FilterSerializer.TYPE to JsonPrimitive("NO SUCH TYPE")))
                } else {
                    element
                }
            },
        ).toString()

        probe.restore(corrupted, names = listOf("A", "B", "C")) shouldBe listOf("B")
    }

    @Test
    fun `manga filters reordered since the save apply to whatever now sits there`() {
        val probe = MangaSavedSearchFiltersProbe()
        val saved = probe.save(names = listOf("A", "B", "C"), chosen = "A")!!

        probe.restore(saved, names = listOf("C", "B", "A")) shouldBe listOf("C")
    }

    @Test
    fun `novel filters reordered since the save still apply to the right one`() {
        val probe = NovelSavedSearchFiltersProbe()
        val saved = probe.save(names = listOf("A", "B", "C"), chosen = "A")!!

        probe.restore(saved, names = listOf("C", "B", "A")) shouldBe listOf("A")
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaSavedSearchFiltersProbe(), NovelSavedSearchFiltersProbe())
    }
}

/**
 * One content type's half of the shared cases, normalized so both answer in the same shape: a source
 * whose filters are [names], one of them switched on, and the names that come back on afterwards.
 */
interface SavedSearchFiltersProbe {

    fun save(names: List<String>, chosen: String?): String?

    /** Decodes onto a state built for [names], with [preset] switched on before decoding. */
    fun restore(json: String, names: List<String>, preset: String? = null): List<String>
}

class MangaSavedSearchFiltersProbe : SavedSearchFiltersProbe {

    private val filters = MangaSavedSearchFilters()

    override fun save(names: List<String>, chosen: String?): String? = filters.encode(state(names, chosen))

    override fun restore(json: String, names: List<String>, preset: String?): List<String> =
        filters.decode(json, state(names, preset))
            .filterIsInstance<Filter.CheckBox>()
            .filter { it.state }
            .map { it.name }

    private fun state(names: List<String>, chosen: String?) =
        FilterList(names.map { TestCheckBox(it, it == chosen) })

    override fun toString() = "manga"

    private class TestCheckBox(name: String, state: Boolean) : Filter.CheckBox(name, state)
}

class NovelSavedSearchFiltersProbe : SavedSearchFiltersProbe {

    private val filters = NovelSavedSearchFilters()

    override fun save(names: List<String>, chosen: String?): String? = filters.encode(state(names, chosen))

    override fun restore(json: String, names: List<String>, preset: String?): List<String> {
        val decoded = filters.decode(json, state(names, preset))
        // Only the filters the source still declares, since the plugin's schema is what the options
        // are built from; a value left over from a filter that is gone never reaches a request.
        return names.filter { decoded[it]?.jsonPrimitive?.boolean == true }
    }

    private fun state(names: List<String>, chosen: String?): Map<String, JsonElement> =
        names.associateWith { JsonPrimitive(it == chosen) }

    override fun toString() = "novel"
}
