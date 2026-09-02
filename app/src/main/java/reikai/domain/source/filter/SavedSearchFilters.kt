package reikai.domain.source.filter

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

/**
 * How one content type turns its browse filter state into the string a saved search stores, and back.
 *
 * The stored column is opaque, so this is the typed slot that gives it meaning: each content type
 * answers for its own [S], and neither can read the other's payload. Both halves owe the same rule,
 * that a state encoded and decoded against an unchanged source comes back unchanged, which
 * `SavedSearchFiltersConformanceTest` runs over both.
 */
interface SavedSearchFilters<S> {

    /** The stored form of [state], or null when it holds nothing worth saving. */
    fun encode(state: S): String?

    /**
     * Applies a stored payload onto [current], the filter state as the source builds it today, and
     * returns the result. Unreadable input leaves [current] untouched rather than throwing, because a
     * saved search that cannot be applied must still open its source.
     */
    fun decode(json: String, current: S): S
}

/**
 * The manga half, over the source's own `FilterList`.
 *
 * [decode] mutates [current] and returns it, because a `Filter` carries its value in a `var` and the
 * list cannot be copied. Hand it a list built for this call, never one a caller still holds.
 */
@Inject
@SingleIn(AppScope::class)
class MangaSavedSearchFilters : SavedSearchFilters<FilterList> {

    // Stateless and not a boundary worth substituting, so it is built here rather than bound.
    private val serializer = FilterSerializer()

    override fun encode(state: FilterList): String? =
        runCatching { serializer.serialize(state) }
            .getOrNull()
            ?.takeIf { it.isNotEmpty() }
            ?.let { Json.encodeToString(it) }

    override fun decode(json: String, current: FilterList): FilterList {
        val stored = runCatching { Json.parseToJsonElement(json).jsonArray }.getOrNull() ?: return current
        serializer.deserialize(current, stored)
        return current
    }
}

/**
 * The light-novel half, over the filter-value map the plugin's schema is read into.
 *
 * Values are keyed by filter, so a source that adds, removes or reorders a filter cannot misapply a
 * saved one the way the positional manga encoding can. A value whose filter is gone stays in the map
 * and is dropped downstream, where the options are built from the schema rather than from this.
 */
@Inject
@SingleIn(AppScope::class)
class NovelSavedSearchFilters : SavedSearchFilters<Map<String, JsonElement>> {

    override fun encode(state: Map<String, JsonElement>): String? =
        state.takeIf { it.isNotEmpty() }?.let { Json.encodeToString(JsonObject(it)) }

    override fun decode(json: String, current: Map<String, JsonElement>): Map<String, JsonElement> {
        val stored = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return current
        return current + stored
    }
}
