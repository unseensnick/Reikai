package reikai.presentation.novel.browse

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import reikai.novel.source.NovelFilterState
import reikai.novel.source.NovelFilters

/** A genre tapped on a novel's details page, matched onto its source's own filters. */
class NovelGenreFilterTest {

    private val plugin = NovelFilters.LnSchema(
        Json.parseToJsonElement(
            """
            {
              "genre": {"type": "Picker", "label": "Genre", "value": "",
                "options": [{"label": "All", "value": ""}, {"label": "Fantasy", "value": "fantasy"}]},
              "tags": {"type": "Checkbox", "label": "Tags", "value": ["kept"],
                "options": [{"label": "Harem", "value": "harem"}]},
              "themes": {"type": "ExcludableCheckboxGroup", "label": "Themes",
                "value": {"include": [], "exclude": ["gore"]},
                "options": [{"label": "Magic", "value": "magic"}]}
            }
            """,
        ).jsonObject,
    )

    private fun values(genre: String) = (plugin.defaultsWithGenre(genre) as NovelFilterState.LnValues).values

    @Test
    fun `a plugin picker moves to the genre`() {
        values("fantasy")["genre"].toString() shouldBe "\"fantasy\""
    }

    @Test
    fun `a plugin checkbox list adds the genre to its defaults`() {
        values("Harem")["tags"].toString() shouldBe """["kept","harem"]"""
    }

    @Test
    fun `a plugin include-exclude group includes the genre and keeps its exclusions`() {
        values("MAGIC")["themes"].toString() shouldBe """{"include":["magic"],"exclude":["gore"]}"""
    }

    @Test
    fun `a plugin with no filter of that name has no match`() {
        plugin.defaultsWithGenre("Horror") shouldBe null
    }

    @Test
    fun `a plugin with no filters has no match`() {
        NovelFilters.LnSchema(JsonObject(emptyMap())).defaultsWithGenre("Fantasy") shouldBe null
    }

    @Test
    fun `a Mihon filter list matches the way manga's does`() {
        val filters = NovelFilters.FilterListSchema {
            FilterList(
                object : Filter.Group<Filter.CheckBox>("Genres", listOf(object : Filter.CheckBox("Action") {})) {},
            )
        }
        val state = filters.defaultsWithGenre("action") as NovelFilterState.Filters
        ((state.list.single() as Filter.Group<*>).state.single() as Filter.CheckBox).state shouldBe true
    }

    @Test
    fun `a Mihon filter list with no filter of that name has no match`() {
        NovelFilters.FilterListSchema {
            FilterList(object : Filter.Text("Author") {})
        }.defaultsWithGenre("Action") shouldBe
            null
    }
}
