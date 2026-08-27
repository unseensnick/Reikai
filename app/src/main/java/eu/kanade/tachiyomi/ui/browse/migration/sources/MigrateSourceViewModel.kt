package eu.kanade.tachiyomi.ui.browse.migration.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import eu.kanade.domain.source.interactor.GetSourcesWithFavoriteCount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.WhileSubscribed
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import logcat.LogPriority
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.source.model.Source
import kotlin.time.Duration.Companion.seconds

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class MigrateSourceViewModel(
    getSourcesWithFavoriteCount: GetSourcesWithFavoriteCount,
) : ViewModel() {

    private val _channel = Channel<Event>(Int.MAX_VALUE)
    val channel = _channel.receiveAsFlow()

    // RK -->
    // Stripped to a provider for the shared migrate list, which sorts, filters by chip and owns the
    // sort header for both content types. What is left is the manga data and its error event.
    // The interactor's own sort still runs and is simply re-done over the merged list; it stays
    // untouched so it keeps syncing.
    val sources: StateFlow<List<Pair<Source, Long>>?> = getSourcesWithFavoriteCount.subscribe()
        .catch {
            logcat(LogPriority.ERROR, it)
            _channel.send(Event.FailedFetchingSourcesWithCount)
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5.seconds), null)
    // RK <--

    sealed interface Event {
        data object FailedFetchingSourcesWithCount : Event
    }
}
