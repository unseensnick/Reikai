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
 * The rule both content types owe a saved search: what the reader picked comes back, an unreadable
 * payload is ignored rather than thrown, and a source that has since added, removed or reordered a
 * filter still applies the rest. Pinned once over both probes instead of as a twin pair.
 *
 * The drift cases are shared because Reikai matches manga filters by kind and name, where the encoding
 * it was ported from matched by position. Background: docs/dev/plans/browse-feed-tab.md.
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

        // D is switched on before decoding and the payload has never heard of it, so it is the only
        // thing here that can tell "apply what was saved" from "replace everything with what was
        // saved". Without it the case passes under either.
        probe.restore(saved, names = listOf("A", "B", "C", "D"), preset = "D") shouldBe listOf("A", "D")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a filter removed since the save leaves the others alone`(probe: SavedSearchFiltersProbe) {
        val saved = probe.save(names = listOf("A", "B", "C"), chosen = "B")!!

        probe.restore(saved, names = listOf("A", "C")).shouldBeEmpty()
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `filters reordered since the save still apply to the right one`(probe: SavedSearchFiltersProbe) {
        val saved = probe.save(names = listOf("A", "B", "C"), chosen = "A")!!

        probe.restore(saved, names = listOf("C", "B", "A")) shouldBe listOf("A")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a renamed filter keeps its default rather than taking a stray value`(probe: SavedSearchFiltersProbe) {
        val saved = probe.save(names = listOf("A", "B", "C"), chosen = "B")!!

        probe.restore(saved, names = listOf("A", "Z", "C")).shouldBeEmpty()
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
    fun `one unreadable filter in a saved manga search costs only that filter`() {
        // What the per-element catch buys: a source that changed one filter's kind loses that filter's
        // saved value, not the whole search. Without it the first bad element aborts the rest.
        val probe = MangaSavedSearchFiltersProbe()
        val saved = probe.save(names = listOf("A", "B", "C"), chosen = "B")!!
        // A still matches its live filter, so it is attempted and throws, where an unknown kind would
        // simply go unmatched and never reach the catch at all.
        val corrupted = JsonArray(
            Json.parseToJsonElement(saved).jsonArray.map { element ->
                if (element.jsonObject["name"]?.jsonPrimitive?.content == "A") {
                    JsonObject(element.jsonObject + ("_cmaps" to JsonPrimitive("not an object")))
                } else {
                    element
                }
            },
        ).toString()

        probe.restore(corrupted, names = listOf("A", "B", "C")) shouldBe listOf("B")
    }

    @Test
    fun `a manga filter is matched by kind as well as by name`() {
        val serializer = FilterSerializer()
        val saved = serializer.serialize(FilterList(TestText("Tag", "typed"), TestCheckBox("Tag", true)))
        // Same name, opposite order, so matching on the name alone would hand each the other's value.
        val fresh = FilterList(TestCheckBox("Tag", false), TestText("Tag", ""))

        serializer.deserialize(fresh, saved)

        fresh.filterIsInstance<Filter.CheckBox>().single().state shouldBe true
    }

    @Test
    fun `a dropdown follows the option it was set to when the source reorders its list`() {
        val serializer = FilterSerializer()
        val saved = serializer.serialize(FilterList(TestSelect("Genre", arrayOf("Action", "Comedy", "Drama"), 2)))
        // Same options, different order, so the saved index 2 now points at Action.
        val fresh = FilterList(TestSelect("Genre", arrayOf("Drama", "Comedy", "Action"), 0))

        serializer.deserialize(fresh, saved)

        fresh.filterIsInstance<Filter.Select<*>>().single().state shouldBe 0
    }

    @Test
    fun `a dropdown whose saved option is past the end of a shorter list does not keep the index`() {
        // The index is what the filter sheet reads straight into `values`, so a stale one out of range
        // takes the screen down rather than showing the wrong thing.
        val serializer = FilterSerializer()
        val saved = serializer.serialize(FilterList(TestSelect("Genre", arrayOf("A", "B", "C", "D"), 3)))
        val fresh = FilterList(TestSelect("Genre", arrayOf("A", "B"), 0))

        serializer.deserialize(fresh, saved)

        fresh.filterIsInstance<Filter.Select<*>>().single().state shouldBe 0
    }

    @Test
    fun `a sort follows the column it was set to when the source reorders its list`() {
        val serializer = FilterSerializer()
        val saved = serializer.serialize(
            FilterList(TestSort("Order", arrayOf("Latest", "Popular"), Filter.Sort.Selection(1, false))),
        )
        val fresh = FilterList(TestSort("Order", arrayOf("Popular", "Latest"), Filter.Sort.Selection(0, true)))

        serializer.deserialize(fresh, saved)

        fresh.filterIsInstance<Filter.Sort>().single().state shouldBe Filter.Sort.Selection(0, false)
    }

    @Test
    fun `two manga filters sharing a name and kind are matched in the order they appear`() {
        // The one case a name cannot separate. Matching consumes in order, so these stay positional
        // among themselves, which is what upstream did for every filter.
        val serializer = FilterSerializer()
        val saved = serializer.serialize(FilterList(TestCheckBox("Dup", true), TestCheckBox("Dup", false)))
        val fresh = FilterList(TestCheckBox("Dup", false), TestCheckBox("Dup", false))

        serializer.deserialize(fresh, saved)

        fresh.filterIsInstance<Filter.CheckBox>().map { it.state } shouldBe listOf(true, false)
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

private class TestCheckBox(name: String, state: Boolean) : Filter.CheckBox(name, state)

private class TestText(name: String, state: String) : Filter.Text(name, state)

private class TestSelect(name: String, values: Array<String>, state: Int) :
    Filter.Select<String>(name, values, state)

private class TestSort(name: String, values: Array<String>, state: Selection?) :
    Filter.Sort(name, values, state)
