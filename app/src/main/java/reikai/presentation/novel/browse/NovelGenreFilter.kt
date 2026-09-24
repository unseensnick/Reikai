package reikai.presentation.novel.browse

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import reikai.domain.source.filter.selectGenre
import reikai.novel.source.NovelFilterState
import reikai.novel.source.NovelFilters

/**
 * The source's default filters with [genre] turned on, the way a genre tapped on a details page
 * searches it, or null when the source offers no filter of that name. A Mihon filter list matches
 * as manga's does; an LNReader plugin matches an option label on its first picker, checkbox list or
 * include/exclude group that has one, ignoring case.
 */
internal fun NovelFilters.defaultsWithGenre(genre: String): NovelFilterState? = when (this) {
    is NovelFilters.FilterListSchema -> defaultState().takeIf { it.list.selectGenre(genre) }
    is NovelFilters.LnSchema -> {
        val defaults = defaultState()
        lnGenreValue(schema, defaults.values, genre)
            ?.let { (key, value) -> defaults.copy(values = defaults.values + (key to value)) }
    }
}

private fun lnGenreValue(
    schema: JsonObject,
    defaults: Map<String, JsonElement>,
    genre: String,
): Pair<String, JsonElement>? {
    for ((key, element) in schema) {
        val filter = element as? JsonObject ?: continue
        val value = optionsOf(filter).firstOrNull { (label, _) -> label.equals(genre, true) }?.second ?: continue
        val current = defaults[key]
        val picked = when (filter["type"]?.jsonPrimitive?.contentOrNull) {
            "Picker" -> JsonPrimitive(value)
            "Checkbox" -> JsonArray((current as? JsonArray).orEmpty() + JsonPrimitive(value))
            "ExcludableCheckboxGroup" -> buildJsonObject {
                val obj = current as? JsonObject
                put("include", JsonArray((obj?.get("include") as? JsonArray).orEmpty() + JsonPrimitive(value)))
                put("exclude", (obj?.get("exclude") as? JsonArray) ?: JsonArray(emptyList()))
            }
            else -> continue
        }
        return key to picked
    }
    return null
}
