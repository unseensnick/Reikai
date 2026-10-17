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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import reikai.domain.source.ReikaiSourcePreferences
import reikai.presentation.stats.StatsLibrary
import reikai.presentation.stats.StatsLibraryLoader
import reikai.presentation.stats.readingTimeByDayAndTitle
import reikai.presentation.stats.weekEnding
import tachiyomi.core.common.util.lang.launchIO
import kotlin.time.Clock

/** Seven days ending on [days]' last, and the chosen day's series with how long each was read. */
@Immutable
data class StatsReadingState(
    val days: List<Pair<LocalDate, Long>>,
    val selected: LocalDate,
    val titles: List<Pair<String, Long>>,
    val isCurrentWeek: Boolean,
)

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class StatsReadingViewModel(
    private val loader: StatsLibraryLoader,
    sourcePreferences: ReikaiSourcePreferences,
) : ViewModel() {

    private val timeZone = TimeZone.currentSystemDefault()
    private val today = Clock.System.now().toLocalDateTime(timeZone).date

    private val library = MutableStateFlow<StatsLibrary?>(null)
    private val week = MutableStateFlow(Week(end = today, selected = today))

    /** Null while the library loads. */
    val state: StateFlow<StatsReadingState?> = combine(
        library.filterNotNull(),
        sourcePreferences.statsContentType.changes(),
        week,
    ) { library, type, week ->
        val sessions = library.sessions(type)
        val byDay = readingTimeByDayAndTitle(sessions, timeZone)
        val names = sessions.associate { it.entryId to it.name }
        StatsReadingState(
            days = weekEnding(week.end) { byDay[it].orEmpty().values.sum() },
            selected = week.selected,
            titles = byDay[week.selected].orEmpty()
                .mapNotNull { (entryId, ms) -> names[entryId]?.let { it to ms } }
                .sortedByDescending { it.second },
            isCurrentWeek = week.end == today,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launchIO { library.value = loader.load() }
    }

    fun selectDay(day: LocalDate) = week.update { it.copy(selected = day) }

    /** Moves the week back, or on ([forward]) by seven days, never past today; its last day is chosen. */
    fun moveWeek(forward: Boolean) = week.update {
        val end = if (forward) minOf(it.end.plus(7, DateTimeUnit.DAY), today) else it.end.minus(7, DateTimeUnit.DAY)
        Week(end = end, selected = end)
    }

    private data class Week(val end: LocalDate, val selected: LocalDate)
}
