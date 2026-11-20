package reikai.presentation.stats.details

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelRepository
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.stats.ReadingBar
import reikai.presentation.stats.ReadingPeriod
import reikai.presentation.stats.ReadingWindow
import reikai.presentation.stats.StatsLibrarySource
import reikai.presentation.stats.StatsReload
import reikai.presentation.stats.readingTimeByDayAndTitle
import reikai.presentation.stats.statsCovers
import tachiyomi.domain.manga.repository.MangaRepository
import kotlin.time.Clock

/**
 * A window of reading time cut into [bars], and the series read in it with how long each was read: across
 * the whole window, or only the [selected] bar's dates once one is picked.
 */
@Immutable
data class StatsReadingState(
    val window: ReadingWindow,
    val bars: List<ReadingBar>,
    val selected: ReadingBar?,
    val titles: List<ReadTitle>,
    val canMoveForward: Boolean,
    val today: LocalDate,
)

/** One series read in the chosen dates, how long, and its cover for Coil once loaded. */
@Immutable
data class ReadTitle(val entryId: EntryId, val name: String, val readMs: Long, val cover: Any? = null)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class StatsReadingViewModel(
    private val loader: StatsLibrarySource,
    sourcePreferences: ReikaiSourcePreferences,
    private val novelRepository: NovelRepository,
    private val mangaRepository: MangaRepository,
) : ViewModel() {

    private val timeZone = TimeZone.currentSystemDefault()

    // Read again on each refresh, so a screen kept across midnight reaches the new day.
    private val today = MutableStateFlow(currentDate())

    private val library = StatsReload(viewModelScope, loader::load)
    private val view = MutableStateFlow(View(ReadingWindow.week(today.value), selected = null))

    // Each day's time per series and each series' name, over the whole log: rebuilt only when the log or the
    // content type changes, so a bar tap or a step only slices it. Shared, since the covers read it too.
    private val byDayAndNames = combine(
        library.value.filterNotNull(),
        sourcePreferences.statsContentType.changes(),
    ) { library, type ->
        val sessions = library.sessions(type)
        readingTimeByDayAndTitle(sessions, timeZone) to sessions.associate { it.entryId to it.name }
    }
        .flowOn(Dispatchers.Default)
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(), replay = 1)

    // The covers of the series the window lists, since one outside the library costs a query of its own, read
    // again only when the window or the log changes. The list shows before its covers; a reload keeps the last
    // covers until the new ones are read.
    private val covers = combine(
        library.value.filterNotNull(),
        byDayAndNames,
        view.map { it.window }.distinctUntilChanged(),
    ) { loaded, (byDay, _), window -> loaded to seriesReadIn(byDay, window) }
        .mapLatest { (loaded, ids) -> statsCovers(ids, loaded, mangaRepository, novelRepository) }
        .onStart { emit(emptyMap()) }

    /** Null while the library loads. */
    val state: StateFlow<StatsReadingState?> = combine(byDayAndNames, view, covers, today) {
            (byDay, names),
            view,
            covers,
            today,
        ->
        readingState(byDay, names, view.window, view.selected, covers, today)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Called each time the screen comes back on screen, so it shows the library as it is now. */
    fun refresh() {
        val before = today.value
        val now = currentDate()
        if (now != before) {
            view.update { it.copy(window = it.window.reanchored(before, now)) }
            today.value = now
        }
        library.reload()
    }

    fun selectPeriod(period: ReadingPeriod) {
        view.value = View(ReadingWindow.current(period, today.value), selected = null)
    }

    fun selectRange(first: LocalDate, last: LocalDate) {
        view.value = View(ReadingWindow(ReadingPeriod.CUSTOM, first, last), selected = null)
    }

    /** Narrows the list to [bar]'s dates; picking the chosen bar again widens it back to the window. */
    fun selectBar(bar: ReadingBar) = view.update {
        it.copy(selected = if (it.selected == bar.first) null else bar.first)
    }

    /** Narrows the list to [bar]'s dates, as a drag along the chart does at each bar it crosses. */
    fun pickBar(bar: ReadingBar) = view.update { it.copy(selected = bar.first) }

    fun move(forward: Boolean) = view.update { View(it.window.shifted(forward, today.value), selected = null) }

    private fun currentDate() = Clock.System.now().toLocalDateTime(timeZone).date

    private data class View(val window: ReadingWindow, val selected: LocalDate?)
}

/** The series [byDay] counts time for on any of [window]'s dates. */
internal fun seriesReadIn(byDay: Map<LocalDate, Map<EntryId, Long>>, window: ReadingWindow): Set<EntryId> =
    window.days().flatMapTo(HashSet()) { byDay[it]?.keys.orEmpty() }

/**
 * The reading-time screen over [window], its list narrowed to the [selected] bar's dates when one is picked.
 * [byDay] and [names] cover the whole log, keyed by the series each session counts under.
 */
internal fun readingState(
    byDay: Map<LocalDate, Map<EntryId, Long>>,
    names: Map<EntryId, String>,
    window: ReadingWindow,
    selected: LocalDate?,
    covers: Map<EntryId, Any>,
    today: LocalDate,
): StatsReadingState {
    val bars = window.bars { byDay[it].orEmpty().values.sum() }
    val selectedBar = bars.firstOrNull { it.first == selected }
    val dates = selectedBar?.let { ReadingWindow(window.period, it.first, it.last) } ?: window
    val byTitle = mutableMapOf<EntryId, Long>()
    dates.days().forEach { day -> byDay[day]?.forEach { (id, ms) -> byTitle[id] = (byTitle[id] ?: 0L) + ms } }
    return StatsReadingState(
        window = window,
        bars = bars,
        selected = selectedBar,
        titles = byTitle
            .mapNotNull { (entryId, ms) -> names[entryId]?.let { ReadTitle(entryId, it, ms, covers[entryId]) } }
            .sortedByDescending { it.readMs },
        canMoveForward = window.last < today,
        today = today,
    )
}
