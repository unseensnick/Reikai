package reikai.novel.source

import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A source's own settings, in the shape its format declares them; a source with none has none of these. */
sealed interface NovelSettings {

    /** An LNReader plugin's `pluginSettings` schema, whose values live in the plugin's own storage. */
    class LnSchema(
        val schema: JsonObject,
        private val read: suspend (key: String) -> JsonElement?,
        private val write: (key: String, value: JsonElement?) -> Unit,
    ) : NovelSettings {
        suspend fun get(key: String): JsonElement? = read(key)

        fun set(key: String, value: JsonElement?) = write(key, value)
    }

    /** A preference screen the source builds itself, as a Mihon `ConfigurableSource` does. */
    class PreferenceScreen(val source: ConfigurableSource) : NovelSettings
}

/** The listings a novel source pages without a query. */
enum class NovelListing { Popular, Latest }

/**
 * A source's filters, in the shape its format declares them. The shape also decides where they apply,
 * because the formats disagree: an LNReader plugin's search takes no options, so its filters narrow the
 * listings, while a Mihon filter list travels with the search, as manga's does. A source that declares
 * no filters has none of these rather than an empty one.
 */
sealed interface NovelFilters {

    val applyToSearch: Boolean

    /** The state before the reader has picked anything, built fresh for each call. */
    fun defaultState(): NovelFilterState

    /** An LNReader plugin's `filters` schema, which the host passes through uninterpreted. */
    data class LnSchema(val schema: JsonObject) : NovelFilters {
        override val applyToSearch: Boolean get() = false

        override fun defaultState() = NovelFilterState.LnValues(defaultFilterValues(schema))
    }

    /** Mihon's filter model, built by the source each time, since a `Filter` carries its value in a `var`. */
    class FilterListSchema(private val build: () -> FilterList) : NovelFilters {
        override val applyToSearch: Boolean get() = true

        override fun defaultState() = NovelFilterState.Filters(build())
    }
}

/** The filters the reader picked, in the shape of the source's [NovelFilters]. */
sealed interface NovelFilterState {

    /** Value per filter key, as the LNReader filter sheet edits them. */
    data class LnValues(val values: Map<String, JsonElement>) : NovelFilterState

    /**
     * Compared by identity on purpose: the list is edited in place, so an Apply wraps it anew to tell
     * the pager something changed, as manga's search listing does by copying its filters in.
     */
    class Filters(val list: FilterList) : NovelFilterState
}
