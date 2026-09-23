package reikai.novel.source

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import reikai.novel.host.ChapterItem
import reikai.novel.host.SourceNovel
import tachiyomi.i18n.MR

/** How a novel source is packaged: an LNReader plugin, or an app built on tachiyomi's or IReader's library. */
enum class NovelExtensionFormat(val label: StringResource) {
    JS(MR.strings.extension_format_js),
    APK(MR.strings.extension_format_apk),
    IREADER(MR.strings.extension_format_ireader),
    ;

    companion object {
        /** Whether a list holding these rows names each one's format: only when it tells them apart. */
        fun tellsApart(formats: Iterable<NovelExtensionFormat?>): Boolean = formats.filterNotNull().toSet().size > 1
    }
}

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

/** What a page loaded in the in-app browser can be used for. */
enum class NovelPageKind { DETAILS, CHAPTERS, CHAPTER_TEXT }

/**
 * A source that parses a page the user loaded themselves rather than one it requests, the way through
 * for a site that blocks its requests. [kinds] is what it takes; only those are offered.
 */
interface NovelPageFetch {

    val kinds: Set<NovelPageKind>

    /** The novel at [novelPath] as the page shows it, with no chapters. */
    suspend fun details(novelPath: String, url: String, html: String): SourceNovel

    suspend fun chapters(novelPath: String, url: String, html: String): List<ChapterItem>

    /** The chapter at [chapterPath] as HTML the readers take, as [NovelSource.parseChapter] returns it. */
    suspend fun chapterText(chapterPath: String, url: String, html: String): String
}
