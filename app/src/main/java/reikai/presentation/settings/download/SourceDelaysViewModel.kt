package reikai.presentation.settings.download

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import reikai.domain.download.DownloadPacing
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.includes
import reikai.domain.library.within
import reikai.domain.manga.MangaPreferences
import reikai.domain.novel.NovelPreferences
import reikai.novel.local.LocalNovelSource
import reikai.novel.source.NovelSourceManager
import tachiyomi.domain.source.service.SourceManager
import kotlin.time.Duration.Companion.seconds

/**
 * Every installed manga and novel source with the delay its downloader keeps between chapters. The chip
 * and the search narrow the list; the Show setting narrows it live, so a chip it hides cannot leave the
 * list filtered by a type the app no longer shows.
 */
@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class SourceDelaysViewModel(
    sourceManager: SourceManager,
    novelSourceManager: NovelSourceManager,
    reikaiLibraryPreferences: ReikaiLibraryPreferences,
    private val mangaPreferences: MangaPreferences,
    private val novelPreferences: NovelPreferences,
) : ViewModel() {

    private val chip = MutableStateFlow(ContentType.ALL)

    // Null while the search field is closed.
    private val query = MutableStateFlow<String?>(null)

    // Whether each source had its own delay when its slider was last let go (or the list first showed it),
    // by row key. A slider saves on every step, so sorting by the live value would move the row being
    // dragged; settle re-sorts the row once the finger lifts.
    private val ownWhenListed = HashMap<String, Boolean>()
    private val settled = MutableStateFlow(0)

    val state: StateFlow<State> = combine(
        sourceDelayRows(sourceManager, novelSourceManager, mangaPreferences, novelPreferences)
            .combine(settled) { rows, _ -> rows },
        chip,
        reikaiLibraryPreferences.shownContent.changes(),
        query,
        combine(
            mangaPreferences.downloadChapterDelayMs().changes(),
            novelPreferences.downloadChapterDelayMs().changes(),
            ::Pair,
        ),
    ) { rows, chip, shown, query, (mangaGlobalMs, novelGlobalMs) ->
        val type = chip.within(shown)
        State(
            sections = sourceDelaySections(rows, type, query.orEmpty()) { row ->
                ownWhenListed.getOrPut(row.key) { row.delayMs != null }
            },
            contentType = type,
            query = query,
            mangaGlobalMs = mangaGlobalMs,
            novelGlobalMs = novelGlobalMs,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), State())

    fun setContentType(type: ContentType) {
        chip.value = type
    }

    fun setQuery(text: String?) {
        query.value = text
    }

    /** Lets [row] move to the section its delay now puts it in, once its slider is let go. */
    fun settle(row: SourceDelay) {
        ownWhenListed.remove(row.key)
        settled.update { it + 1 }
    }

    /** Gives [row]'s source its own delay, or hands it back to the global one when [delayMs] is null. */
    fun setDelay(row: SourceDelay, delayMs: Long?) {
        val preference = delaysOf(row.type)
        val delays = DownloadPacing.parse(preference.get())
        val updated = if (delayMs == null) delays - row.id else delays + (row.id to delayMs)
        preference.set(DownloadPacing.format(updated))
    }

    /** Hands every one of [rows] back to its type's global delay, and lets each move out of Own delay. */
    fun resetDelays(rows: List<SourceDelay>) {
        rows.groupBy { it.type }.forEach { (type, ofType) ->
            val preference = delaysOf(type)
            preference.set(DownloadPacing.format(DownloadPacing.parse(preference.get()) - ofType.map { it.id }.toSet()))
        }
        rows.forEach { ownWhenListed.remove(it.key) }
        settled.update { it + 1 }
    }

    private fun delaysOf(type: ContentType) =
        if (type ==
            ContentType.MANGA
        ) {
            mangaPreferences.downloadSourceDelays()
        } else {
            novelPreferences.downloadSourceDelays()
        }

    /** [sections] is null until the sources have loaded. */
    @Immutable
    data class State(
        val sections: SourceDelaySections? = null,
        val contentType: ContentType = ContentType.ALL,
        val query: String? = null,
        val mangaGlobalMs: Long = 0L,
        val novelGlobalMs: Long = 0L,
    )
}

/** [delayMs] is the source's own delay as stored; a novel's downloader lifts it to [minimumMs]. */
@Immutable
data class SourceDelay(
    val type: ContentType,
    val id: String,
    val name: String,
    val lang: String,
    val delayMs: Long?,
    val minimumMs: Long,
) {
    val key: String get() = "$type:$id"
}

/**
 * Every installed manga and novel source with its own delay as stored, keyed by the id its downloader reads.
 * A delay stored for a source not installed is kept, so a reinstall gets it back, but is not a row.
 */
fun sourceDelayRows(
    sourceManager: SourceManager,
    novelSourceManager: NovelSourceManager,
    mangaPreferences: MangaPreferences,
    novelPreferences: NovelPreferences,
): Flow<List<SourceDelay>> = combine(
    sourceManager.sources.map { all ->
        all.filterIsInstance<HttpSource>()
            .map { SourceDelay(ContentType.MANGA, it.id.toString(), it.name, it.lang, null, 0L) }
    },
    // Only sources that download over the network: the local ones read files, as manga's HttpSource filter says.
    novelSourceManager.loadedSources().map { all ->
        all.filterNot { LocalNovelSource.isLocal(it.id) }
            .map { SourceDelay(ContentType.NOVELS, it.id, it.name, it.lang, null, it.minimumRequestDelayMs) }
    },
    mangaPreferences.downloadSourceDelays().changes(),
    novelPreferences.downloadSourceDelays().changes(),
) { manga, novels, mangaEntries, novelEntries ->
    val mangaOwn = DownloadPacing.parse(mangaEntries)
    val novelOwn = DownloadPacing.parse(novelEntries)
    manga.map { it.copy(delayMs = mangaOwn[it.id]) } + novels.map { it.copy(delayMs = novelOwn[it.id]) }
}

/** How many of the [shown] types' sources have their own delay, counted as the list shows them. */
fun List<SourceDelay>.ownDelayCount(shown: ContentType): Int = count { it.delayMs != null && shown.includes(it.type) }

/** The sources with their own delay first, then the rest of each type, each by name. */
@Immutable
data class SourceDelaySections(
    val own: List<SourceDelay>,
    val manga: List<SourceDelay>,
    val novels: List<SourceDelay>,
) {
    val isEmpty: Boolean get() = own.isEmpty() && manga.isEmpty() && novels.isEmpty()
}

/**
 * [rows] narrowed to [type] (already resolved against the Show setting) and to names matching [query], with
 * the rows [isOwn] says first.
 */
internal fun sourceDelaySections(
    rows: List<SourceDelay>,
    type: ContentType,
    query: String,
    isOwn: (SourceDelay) -> Boolean,
): SourceDelaySections {
    val shown = rows
        .filter { type.includes(it.type) && (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) }
        .sortedBy { it.name.lowercase() }
    val (own, rest) = shown.partition(isOwn)
    return SourceDelaySections(
        own = own,
        manga = rest.filter { it.type == ContentType.MANGA },
        novels = rest.filter { it.type == ContentType.NOVELS },
    )
}
