package reikai.presentation.track

import android.content.Context
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLocale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.SavedStateHandleSaveableApi
import androidx.lifecycle.viewmodel.compose.saveable
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import dev.icerock.moko.resources.StringResource
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.domain.track.model.toDbTrack
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.track.TrackChapterSelector
import eu.kanade.presentation.track.TrackDateSelector
import eu.kanade.presentation.track.TrackInfoDialogHome
import eu.kanade.presentation.track.TrackScoreSelector
import eu.kanade.presentation.track.TrackStatusSelector
import eu.kanade.presentation.track.TrackerSearch
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.ReplacingWriteTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import eu.kanade.tachiyomi.util.lang.convertEpochMillisZone
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.Serializable
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.core.metro.AppCoroutineScope
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.Navigator
import mihon.core.navigation.NavigatorDisplay
import mihon.core.navigation.rememberNavigator
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.roundedfilled.Delete
import mihon.icons.materialsymbols.roundedfilled.Warning
import reikai.domain.entry.EntryId
import reikai.domain.track.EntryTrackPort
import reikai.domain.track.EntryTrackPorts
import reikai.domain.track.RemoteFirstRemoval
import reikai.domain.track.TrackWriter
import reikai.domain.track.autobind.AutoBindTracker
import reikai.domain.track.autobind.AutoBindTrackers
import reikai.domain.track.autobind.offerTrackers
import reikai.domain.track.removeTrack
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.AlertDialogContent
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import kotlin.time.Clock
import kotlin.time.Instant
import eu.kanade.tachiyomi.data.database.models.Track as DbTrack

/**
 * The single track-info dialog stack for both manga and novels: the same domain [Track] (novels adapt
 * via [reikai.domain.novel.track.toUiTrack]) written through a [TrackWriter], so the two cannot drift.
 * Whatever is engine-specific goes through the entry's [EntryTrackPort], which [EntryTrackPorts] picks
 * from the [EntryId] once, so no screen here branches on the content type.
 * A tracker that binds entries from a source it knows ([AutoBindTracker]) matches on a tap instead of
 * searching for those entries; [offerTrackers] decides the rows, for both types.
 */
@Serializable
data class EntryTrackInfoDialogHomeRoute(val entry: EntryId) : NavKey {

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val entry: EntryId,
        @AppCoroutineScope private val appScope: CoroutineScope,
        private val context: Context,
        private val trackerManager: TrackerManager,
        private val autoBindTrackers: AutoBindTrackers,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State())

        /** The tracker whose replace confirmation is up, held here so a rotation keeps it. */
        val pendingReplace: StateFlow<Long?>
            field = MutableStateFlow<Long?>(null)

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(entry: EntryId): Model
        }

        private val port = ports.of(entry)

        init {
            viewModelScope.launch { refreshTrackers() }

            viewModelScope.launch {
                port.tracks()
                    .catch { logcat(LogPriority.ERROR, it) }
                    .distinctUntilChanged()
                    .map { it.toState() }
                    .collectLatest { next -> state.update { next } }
            }
        }

        /** A tracker that knows the entry's source binds it to its match with no manual search. */
        fun registerAutoBind(item: TrackItem) {
            val candidate = autoBindTrackers.of(item.tracker) ?: return
            appScope.launch {
                val bindEntry = port.autoBindEntry() ?: return@launch
                // A null match is the source having no counterpart; a throw is the tracker failing.
                val match = runCatchingCancellable { candidate.match(bindEntry) }.getOrElse { e ->
                    logcat(LogPriority.ERROR, e) { "Failed to match entry=$entry on ${item.tracker.name}" }
                    withUIContext { context.toast(context.trackerErrorMessage(item.tracker.name, e)) }
                    return@launch
                }
                if (match == null) {
                    withUIContext { context.toast(MR.strings.error_no_match) }
                    return@launch
                }
                bindTrack(context, port, item.tracker, match)
            }
        }

        fun askToReplace(item: TrackItem) = pendingReplace.update { item.tracker.id }

        fun dismissReplace() = pendingReplace.update { null }

        private suspend fun refreshTrackers() {
            val failed = port.refresh().mapNotNull { (tracker, e) -> tracker?.let { it to e } }
            failed.forEach { (tracker, e) ->
                logcat(LogPriority.ERROR, e) { "Failed to refresh track data entry=$entry for service ${tracker.id}" }
            }
            val message = context.trackerFailuresMessage(failed) ?: return
            withUIContext { context.toast(message) }
        }

        fun togglePrivate(item: TrackItem) {
            appScope.launch {
                port.writer.setRemotePrivate(item.tracker, item.track!!.toDbTrack(), !item.track.private)
            }
        }

        private suspend fun List<Track>.toState(): State {
            val offer = offerTrackers(port, trackerManager.loggedInTrackers(), autoBindTrackers)
            return State(
                trackItems = offer.offered.map { service -> TrackItem(find { it.trackerId == service.id }, service) },
                autoMatchTrackerIds = offer.matchedByTap,
            )
        }

        @Immutable
        data class State(
            val trackItems: List<TrackItem> = emptyList(),
            /** Trackers a tap binds by matching the entry's source, rather than opening a search. */
            val autoMatchTrackerIds: Set<Long> = emptySet(),
        )
    }
}

@Composable
fun EntryTrackInfoDialogHomeScreen(entry: EntryId) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val locale = LocalLocale.current
    val viewModel =
        assistedMetroViewModel<EntryTrackInfoDialogHomeRoute.Model, EntryTrackInfoDialogHomeRoute.Model.Factory> {
            create(entry = entry)
        }

    val dateFormat = remember {
        UiPreferences.dateFormat(
            context.appGraph.uiPreferences.dateFormat.get(),
            locale.platformLocale,
        )
    }
    val state by viewModel.state.collectAsState()
    val pendingReplace by viewModel.pendingReplace.collectAsState()

    TrackInfoDialogHome(
        trackItems = state.trackItems,
        dateFormat = dateFormat,
        onStatusClick = { navigator.push(EntryTrackStatusSelectorRoute(it.tracker.id, entry)) },
        onChapterClick = { navigator.push(EntryTrackChapterSelectorRoute(it.tracker.id, entry)) },
        onScoreClick = { navigator.push(EntryTrackScoreSelectorRoute(it.tracker.id, entry)) },
        onStartDateEdit = { navigator.push(EntryTrackDateSelectorRoute(it.tracker.id, start = true, entry)) },
        onEndDateEdit = { navigator.push(EntryTrackDateSelectorRoute(it.tracker.id, start = false, entry)) },
        onNewSearch = {
            if (it.tracker.id in state.autoMatchTrackerIds) {
                if (it.tracker is ReplacingWriteTracker) viewModel.askToReplace(it) else viewModel.registerAutoBind(it)
            } else {
                navigator.push(EntryTrackerSearchRoute(entry, it.tracker.id))
            }
        },
        onOpenInBrowser = { openTrackerInBrowser(context, it) },
        onRemoved = { navigator.push(EntryTrackerRemoveRoute(entry, it.tracker.id)) },
        onCopyLink = { context.copyTrackerLink(it) },
        onTogglePrivate = viewModel::togglePrivate,
    )

    state.trackItems.find { it.tracker.id == pendingReplace }?.let { item ->
        ReplaceEntryConfirmDialog(
            trackerName = item.tracker.name,
            onConfirm = {
                viewModel.dismissReplace()
                viewModel.registerAutoBind(item)
            },
            onDismissRequest = viewModel::dismissReplace,
        )
    }
}

private fun openTrackerInBrowser(context: Context, trackItem: TrackItem) {
    val url = trackItem.track?.remoteUrl ?: return
    if (url.isNotBlank()) context.openInBrowser(url)
}

private fun Context.copyTrackerLink(trackItem: TrackItem) {
    val url = trackItem.track?.remoteUrl ?: return
    if (url.isNotBlank()) copyToClipboard(url, url)
}

/** [trackerId]'s binding among the entry's group tracks, picked as the home page lists it. */
private suspend fun EntryTrackPort.trackFor(trackerId: Long): Track? =
    tracks().first().find { it.trackerId == trackerId }

/** What a picker page holds of its binding: still being read, read, or gone before the page opened on it. */
sealed interface TrackPage<out T> {
    data object Loading : TrackPage<Nothing>

    data class Loaded<T>(val value: T) : TrackPage<T>

    data object Gone : TrackPage<Nothing>
}

private suspend fun <T> EntryTrackPort.pageFor(trackerId: Long, toValue: (Track) -> T): TrackPage<T> =
    trackFor(trackerId)?.let { TrackPage.Loaded(toValue(it)) } ?: TrackPage.Gone

private val <T> TrackPage<T>.loaded: T? get() = (this as? TrackPage.Loaded)?.value

private fun <T> MutableStateFlow<TrackPage<T>>.updateLoaded(transform: (T) -> T) =
    update { if (it is TrackPage.Loaded) TrackPage.Loaded(transform(it.value)) else it }

/**
 * Every picker page and the remove page draw through here, so none confirms on a binding it has not read. A
 * spinner holds the sheet open while the read runs; a binding gone by then says so and pops, since an empty
 * page would leave the sheet up with only a scrim tap to close it.
 */
@Composable
internal fun <T> TrackPageContent(page: TrackPage<T>, onGone: () -> Unit, content: @Composable (T) -> Unit) {
    when (page) {
        TrackPage.Loading -> LoadingScreen(Modifier.height(TRACK_PAGE_LOADING_HEIGHT.dp))
        TrackPage.Gone -> {
            val context = LocalContext.current
            LaunchedEffect(Unit) {
                context.toast(MR.strings.entry_not_tracked)
                onGone()
            }
        }
        is TrackPage.Loaded -> content(page.value)
    }
}

private const val TRACK_PAGE_LOADING_HEIGHT = 120

/**
 * Applies [edit] to the binding as stored now, never to a copy read when the page opened: the write
 * pushes the whole row, so a stale one would send old progress, score and dates back to the tracker.
 */
private suspend fun EntryTrackPort.editTrack(trackerId: Long, edit: suspend TrackWriter.(DbTrack) -> Unit) {
    val track = trackFor(trackerId) ?: return
    writer.edit(track.toDbTrack())
}

// The pickers' routes carry the tracker and the entry only, so a restored sheet reads the binding afresh,
// and each page draws through [TrackPageContent] once that read lands.

@Serializable
data class EntryTrackStatusSelectorRoute(
    val serviceId: Long,
    val entry: EntryId,
) : NavKey {

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val trackerId: Long,
        @Assisted entry: EntryId,
        @AppCoroutineScope private val appScope: CoroutineScope,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        val state: StateFlow<TrackPage<Model.State>>
            field = MutableStateFlow<TrackPage<Model.State>>(TrackPage.Loading)

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(trackerId: Long, entry: EntryId): Model
        }

        // The tracker and the port are derived from the ids rather than passed in, so the dialog
        // stops resolving DI from a composable body. Every selector model below does the same.
        private val tracker = trackerManager.get(trackerId)!!
        private val port = ports.of(entry)

        init {
            viewModelScope.launch { state.value = port.pageFor(trackerId) { State(it.status) } }
        }

        fun getSelections(): Map<Long, StringResource?> =
            tracker.getStatusList().associateWith { tracker.getStatus(it) }

        fun setSelection(selection: Long) = state.updateLoaded { it.copy(selection = selection) }

        fun setStatus() {
            val selection = state.value.loaded?.selection ?: return
            appScope.launch { port.editTrack(trackerId) { setRemoteStatus(tracker, it, selection) } }
        }

        @Immutable
        data class State(val selection: Long)
    }
}

@Composable
fun EntryTrackStatusSelectorScreen(serviceId: Long, entry: EntryId) {
    val navigator = LocalNavigator.current
    val viewModel =
        assistedMetroViewModel<EntryTrackStatusSelectorRoute.Model, EntryTrackStatusSelectorRoute.Model.Factory> {
            create(trackerId = serviceId, entry = entry)
        }
    val page by viewModel.state.collectAsState()
    TrackPageContent(page, onGone = navigator::pop) { state ->
        TrackStatusSelector(
            selection = state.selection,
            onSelectionChange = viewModel::setSelection,
            selections = remember { viewModel.getSelections() },
            onConfirm = {
                viewModel.setStatus()
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
        )
    }
}

@Serializable
data class EntryTrackChapterSelectorRoute(
    val serviceId: Long,
    val entry: EntryId,
) : NavKey {

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val trackerId: Long,
        @Assisted entry: EntryId,
        @AppCoroutineScope private val appScope: CoroutineScope,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        val state: StateFlow<TrackPage<Model.State>>
            field = MutableStateFlow<TrackPage<Model.State>>(TrackPage.Loading)

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(trackerId: Long, entry: EntryId): Model
        }

        private val tracker = trackerManager.get(trackerId)!!
        private val port = ports.of(entry)

        init {
            viewModelScope.launch {
                state.value = port.pageFor(trackerId) { track ->
                    val endRange = if (track.totalChapters > 0) track.totalChapters else 10000
                    State(track.lastChapterRead.toInt(), 0..endRange.toInt(), track.lastChapterRead)
                }
            }
        }

        fun setSelection(selection: Int) = state.updateLoaded { it.copy(selection = selection) }

        fun setChapter() {
            val state = state.value.loaded ?: return
            // The picker holds whole chapters, so an unmoved one would truncate 12.5 to 12.
            if (state.selection == state.opened.toInt()) return
            appScope.launch { port.editTrack(trackerId) { setRemoteLastChapterRead(tracker, it, state.selection) } }
        }

        @Immutable
        data class State(val selection: Int, val range: IntRange, val opened: Double)
    }
}

@Composable
fun EntryTrackChapterSelectorScreen(serviceId: Long, entry: EntryId) {
    val navigator = LocalNavigator.current
    val viewModel =
        assistedMetroViewModel<EntryTrackChapterSelectorRoute.Model, EntryTrackChapterSelectorRoute.Model.Factory> {
            create(trackerId = serviceId, entry = entry)
        }
    val page by viewModel.state.collectAsState()
    TrackPageContent(page, onGone = navigator::pop) { state ->
        TrackChapterSelector(
            selection = state.selection,
            onSelectionChange = viewModel::setSelection,
            range = state.range,
            onConfirm = {
                viewModel.setChapter()
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
        )
    }
}

@Serializable
data class EntryTrackScoreSelectorRoute(
    val serviceId: Long,
    val entry: EntryId,
) : NavKey {

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val trackerId: Long,
        @Assisted entry: EntryId,
        @AppCoroutineScope private val appScope: CoroutineScope,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        private val tracker = trackerManager.get(trackerId)!!
        private val port = ports.of(entry)

        val state: StateFlow<TrackPage<Model.State>>
            field = MutableStateFlow<TrackPage<Model.State>>(TrackPage.Loading)

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(trackerId: Long, entry: EntryId): Model
        }

        init {
            viewModelScope.launch { state.value = port.pageFor(trackerId) { State(tracker.displayScore(it)) } }
        }

        fun getSelections(): List<String> = tracker.getScoreList()

        fun setSelection(selection: String) = state.updateLoaded { it.copy(selection = selection) }

        fun setScore() {
            val selection = state.value.loaded?.selection ?: return
            appScope.launch { port.editTrack(trackerId) { setRemoteScore(tracker, it, selection) } }
        }

        @Immutable
        data class State(val selection: String)
    }
}

@Composable
fun EntryTrackScoreSelectorScreen(serviceId: Long, entry: EntryId) {
    val navigator = LocalNavigator.current
    val viewModel =
        assistedMetroViewModel<EntryTrackScoreSelectorRoute.Model, EntryTrackScoreSelectorRoute.Model.Factory> {
            create(trackerId = serviceId, entry = entry)
        }
    val page by viewModel.state.collectAsState()
    TrackPageContent(page, onGone = navigator::pop) { state ->
        TrackScoreSelector(
            selection = state.selection,
            onSelectionChange = viewModel::setSelection,
            selections = remember { viewModel.getSelections() },
            onConfirm = {
                viewModel.setScore()
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
        )
    }
}

@Serializable
data class EntryTrackDateSelectorRoute(
    val serviceId: Long,
    val start: Boolean,
    val entry: EntryId,
) : NavKey {

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val trackerId: Long,
        @Assisted private val start: Boolean,
        @Assisted private val entry: EntryId,
        @AppCoroutineScope private val appScope: CoroutineScope,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        /** The binding as the page opened on it, which seeds the calendar and its bounds. */
        val track: StateFlow<TrackPage<Track>>
            field = MutableStateFlow<TrackPage<Track>>(TrackPage.Loading)

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(trackerId: Long, start: Boolean, entry: EntryId): Model
        }

        private val tracker = trackerManager.get(trackerId)!!
        private val port = ports.of(entry)

        init {
            viewModelScope.launch { track.value = port.pageFor(trackerId) { it } }
        }

        // In UTC
        fun initialSelection(track: Track): Long {
            val date = (if (start) track.startDate else track.finishDate) ?: Clock.System.now()
            return date.toEpochMilliseconds().convertEpochMillisZone(TimeZone.currentSystemDefault(), TimeZone.UTC)
        }

        // In UTC
        fun setDate(millis: Long) {
            // Convert to local time
            val localMillis = millis.convertEpochMillisZone(TimeZone.UTC, TimeZone.currentSystemDefault())
            appScope.launch {
                port.editTrack(trackerId) {
                    if (start) {
                        setRemoteStartDate(
                            tracker,
                            it,
                            localMillis,
                        )
                    } else {
                        setRemoteFinishDate(tracker, it, localMillis)
                    }
                }
            }
        }

        fun confirmRemoveDate(navigator: Navigator) {
            navigator.push(EntryTrackDateRemoverRoute(tracker.id, start, entry))
        }
    }
}

@Composable
fun EntryTrackDateSelectorScreen(serviceId: Long, start: Boolean, entry: EntryId) {
    val navigator = LocalNavigator.current
    val viewModel =
        assistedMetroViewModel<EntryTrackDateSelectorRoute.Model, EntryTrackDateSelectorRoute.Model.Factory> {
            create(trackerId = serviceId, start = start, entry = entry)
        }
    val page by viewModel.track.collectAsState()
    TrackPageContent(page, onGone = navigator::pop) { track ->
        val selectableDates = remember(track, start) { trackSelectableDates(track, start) }

        val canRemove = if (start) track.startDate != null else track.finishDate != null
        TrackDateSelector(
            title = if (start) {
                stringResource(MR.strings.track_started_reading_date)
            } else {
                stringResource(MR.strings.track_finished_reading_date)
            },
            initialSelectedDateMillis = remember(track) { viewModel.initialSelection(track) },
            selectableDates = selectableDates,
            onConfirm = {
                viewModel.setDate(it)
                navigator.pop()
            },
            onRemove = { viewModel.confirmRemoveDate(navigator) }.takeIf { canRemove },
            onDismissRequest = navigator::pop,
        )
    }
}

private fun trackSelectableDates(track: Track, start: Boolean): SelectableDates = object : SelectableDates {
    override fun isSelectableDate(utcTimeMillis: Long): Boolean {
        val targetDate = Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC)

        // Disallow future dates
        if (targetDate > Clock.System.now().toLocalDateTime(TimeZone.UTC)) return false

        val finishDate = track.finishDate?.toLocalDateTime(TimeZone.UTC)
        val startDate = track.startDate?.toLocalDateTime(TimeZone.UTC)
        return when {
            // Disallow setting start date after finish date
            start && finishDate != null -> {
                targetDate <= finishDate
            }
            // Disallow setting finish date before start date
            !start && startDate != null -> {
                startDate <= targetDate
            }
            else -> true
        }
    }

    override fun isSelectableYear(year: Int): Boolean {
        // Disallow future years
        if (year > Clock.System.now().toLocalDateTime(TimeZone.UTC).year) return false

        val finishDate = track.finishDate?.toLocalDateTime(TimeZone.UTC)
        val startDate = track.startDate?.toLocalDateTime(TimeZone.UTC)
        return when {
            // Disallow setting start year after finish year
            start && finishDate != null -> {
                year <= finishDate.year
            }
            // Disallow setting finish year before start year
            !start && startDate != null -> {
                startDate.year <= year
            }
            else -> true
        }
    }
}

@Serializable
data class EntryTrackDateRemoverRoute(
    val serviceId: Long,
    val start: Boolean,
    val entry: EntryId,
) : NavKey {

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val trackerId: Long,
        @Assisted private val start: Boolean,
        @Assisted entry: EntryId,
        @AppCoroutineScope private val appScope: CoroutineScope,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(trackerId: Long, start: Boolean, entry: EntryId): Model
        }

        private val tracker = trackerManager.get(trackerId)!!
        private val port = ports.of(entry)

        fun getServiceName() = tracker.name

        fun removeDate() {
            appScope.launch {
                port.editTrack(trackerId) {
                    if (start) setRemoteStartDate(tracker, it, 0) else setRemoteFinishDate(tracker, it, 0)
                }
            }
        }
    }
}

@Composable
fun EntryTrackDateRemoverScreen(serviceId: Long, start: Boolean, entry: EntryId) {
    val navigator = LocalNavigator.current
    val viewModel = assistedMetroViewModel<EntryTrackDateRemoverRoute.Model, EntryTrackDateRemoverRoute.Model.Factory> {
        create(trackerId = serviceId, start = start, entry = entry)
    }
    AlertDialogContent(
        modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
        icon = { Icon(imageVector = MaterialSymbols.RoundedFilled.Delete, contentDescription = null) },
        title = {
            Text(
                text = stringResource(MR.strings.track_remove_date_conf_title),
                textAlign = TextAlign.Center,
            )
        },
        text = {
            val serviceName = viewModel.getServiceName()
            Text(
                text = if (start) {
                    stringResource(MR.strings.track_remove_start_date_conf_text, serviceName)
                } else {
                    stringResource(MR.strings.track_remove_finish_date_conf_text, serviceName)
                },
            )
        },
        buttons = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small, Alignment.End),
            ) {
                TextButton(onClick = navigator::pop) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
                FilledTonalButton(
                    onClick = {
                        viewModel.removeDate()
                        navigator.popTo { it is EntryTrackInfoDialogHomeRoute }
                    },
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(text = stringResource(MR.strings.action_remove))
                }
            }
        },
    )
}

@Serializable
data class EntryTrackerSearchRoute(
    val entry: EntryId,
    val serviceId: Long,
) : NavKey {

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted entry: EntryId,
        @Assisted private val trackerId: Long,
        @Assisted savedState: SavedStateHandle,
        @AppCoroutineScope private val appScope: CoroutineScope,
        private val context: Context,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State())

        // In the model, since its prefill is read by id after the page opens, and saved with the page, so a
        // typed query outlives the process
        @OptIn(SavedStateHandleSaveableApi::class)
        val query: TextFieldState = savedState.saveable(key = "query", saver = TextFieldState.Saver) {
            TextFieldState()
        }

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(entry: EntryId, trackerId: Long, savedState: SavedStateHandle): Model
        }

        private val tracker = trackerManager.get(trackerId)!!
        private val port = ports.of(entry)

        private var searchJob: Job? = null

        /** The bound entry's page, which a result matching it is preselected by. */
        private var currentUrl: String? = null

        val supportsPrivateTracking = tracker.supportsPrivateTracking

        val replacesRemoteEntry = tracker is ReplacingWriteTracker

        val trackerName = tracker.name

        init {
            val restored = query.text.toString()
            viewModelScope.launch {
                val track = port.trackFor(trackerId)
                currentUrl = track?.remoteUrl
                val prefill = track?.title ?: port.title().orEmpty()
                // A query restored with the page, or typed while the reads ran, is the user's to keep
                val initialQuery = when {
                    restored.isNotEmpty() -> restored
                    query.text.isNotEmpty() -> return@launch
                    else -> prefill.also(query::setTextAndPlaceCursorAtEnd)
                }
                // Run search on first launch
                if (initialQuery.isNotBlank()) trackingSearch(initialQuery)
            }
        }

        fun trackingSearch(query: String) {
            // A late result from an earlier search would replace this one's
            searchJob?.cancel()
            searchJob = viewModelScope.launch {
                // To show loading state
                state.update { it.copy(queryResult = null, selected = null) }

                val result = withIOContext {
                    try {
                        Result.success(port.search(tracker, query))
                    } catch (e: Throwable) {
                        if (e is CancellationException) throw e
                        Result.failure(e)
                    }
                }
                state.update { oldState ->
                    oldState.copy(
                        queryResult = result,
                        selected = result.getOrNull()?.find { it.tracking_url == currentUrl },
                    )
                }
            }
        }

        fun registerTracking(item: TrackSearch) {
            appScope.launch {
                bindTrack(context, port, tracker, item)
            }
        }

        fun updateSelection(selected: TrackSearch) = state.update { it.copy(selected = selected) }

        fun askToReplace(selected: TrackSearch) = state.update { it.copy(pendingBind = selected) }

        fun dismissReplace() = state.update { it.copy(pendingBind = null) }

        @Immutable
        data class State(
            val queryResult: Result<List<TrackSearch>>? = null,
            val selected: TrackSearch? = null,
            /** The pick whose replace confirmation is up, held here so a rotation keeps it. */
            val pendingBind: TrackSearch? = null,
        )
    }
}

@Composable
fun EntryTrackerSearchScreen(entry: EntryId, serviceId: Long) {
    val navigator = LocalNavigator.current
    val viewModel = assistedMetroViewModel<EntryTrackerSearchRoute.Model, EntryTrackerSearchRoute.Model.Factory> {
        create(entry = entry, trackerId = serviceId, savedState = it.createSavedStateHandle())
    }

    val state by viewModel.state.collectAsState()

    TrackerSearch(
        state = viewModel.query,
        onDispatchQuery = { viewModel.trackingSearch(viewModel.query.text.toString()) },
        queryResult = state.queryResult,
        selected = state.selected,
        onSelectedChange = viewModel::updateSelection,
        onConfirmSelection = f@{ private: Boolean ->
            val selected = state.selected ?: return@f
            selected.private = private
            if (viewModel.replacesRemoteEntry) {
                viewModel.askToReplace(selected)
            } else {
                viewModel.registerTracking(selected)
                navigator.pop()
            }
        },
        onDismissRequest = navigator::pop,
        supportsPrivateTracking = viewModel.supportsPrivateTracking,
    )

    state.pendingBind?.let { selected ->
        ReplaceEntryConfirmDialog(
            trackerName = viewModel.trackerName,
            onConfirm = {
                viewModel.dismissReplace()
                viewModel.registerTracking(selected)
                navigator.pop()
            },
            onDismissRequest = viewModel::dismissReplace,
        )
    }
}

@Serializable
data class EntryTrackerRemoveRoute(
    val entry: EntryId,
    val serviceId: Long,
) : NavKey {

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted entry: EntryId,
        @Assisted private val trackerId: Long,
        private val removal: RemoteFirstRemoval,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(entry: EntryId, trackerId: Long): Model
        }

        private val tracker = trackerManager.get(trackerId)!!
        private val port = ports.of(entry)

        /** The binding the service delete names, read by id when the page opens. */
        val track: StateFlow<TrackPage<Track>>
            field = MutableStateFlow<TrackPage<Track>>(TrackPage.Loading)

        init {
            viewModelScope.launch { track.value = port.pageFor(trackerId) { it } }
        }

        fun getName() = tracker.name

        fun isDeletable() = tracker is DeletableTracker

        // Cleared from every merged source, so a sibling's row can't keep the tracker alive in the
        // library's tracker filter, sort and grouping. Not on viewModelScope: the screen pops as the
        // removal starts.
        fun remove(alsoFromService: Boolean) {
            val track = track.value.loaded ?: return
            port.removeTrack(removal, tracker, track, alsoFromService)
        }
    }
}

@Composable
fun EntryTrackerRemoveScreen(entry: EntryId, serviceId: Long) {
    val navigator = LocalNavigator.current
    val viewModel = assistedMetroViewModel<EntryTrackerRemoveRoute.Model, EntryTrackerRemoveRoute.Model.Factory> {
        create(entry = entry, trackerId = serviceId)
    }
    val serviceName = viewModel.getName()
    var removeRemoteTrack by remember { mutableStateOf(false) }
    val page by viewModel.track.collectAsState()
    TrackPageContent(page, onGone = navigator::pop) {
        AlertDialogContent(
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
            icon = { Icon(imageVector = MaterialSymbols.RoundedFilled.Delete, contentDescription = null) },
            title = {
                Text(
                    text = stringResource(MR.strings.track_delete_title, serviceName),
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                    Text(text = stringResource(MR.strings.track_delete_text, serviceName))
                    if (viewModel.isDeletable()) {
                        LabeledCheckbox(
                            label = stringResource(MR.strings.track_delete_remote_text, serviceName),
                            checked = removeRemoteTrack,
                            onCheckedChange = { removeRemoteTrack = it },
                        )
                    }
                }
            },
            buttons = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small, Alignment.End),
                ) {
                    TextButton(onClick = navigator::pop) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                    FilledTonalButton(
                        onClick = {
                            viewModel.remove(alsoFromService = removeRemoteTrack)
                            navigator.pop()
                        },
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                }
            },
        )
    }
}

/** Asked before binding a [ReplacingWriteTracker], since the bind's write clears what it does not carry. */
@Composable
private fun ReplaceEntryConfirmDialog(
    trackerName: String,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        icon = { Icon(imageVector = MaterialSymbols.RoundedFilled.Warning, contentDescription = null) },
        title = { Text(text = stringResource(MR.strings.track_replace_entry_title, trackerName)) },
        text = { Text(text = stringResource(MR.strings.track_replace_entry_text, trackerName)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = stringResource(MR.strings.action_track))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}

/**
 * The one bind both sheets call, with one error path for both types: a failure toasts what went wrong.
 * Manga's register already catches and toasts; a novel bind would otherwise reach the crash handler.
 */
private suspend fun bindTrack(context: Context, port: EntryTrackPort, tracker: Tracker, item: TrackSearch) {
    try {
        port.bind(tracker, item)
    } catch (e: Throwable) {
        withUIContext { context.toast(context.trackerErrorMessage(tracker.name, e)) }
    }
}

/** The track sheet over [entry]'s details page, drawn as an overlay so the page stays beneath it. */
@Serializable
data class EntryTrackSheetRoute(val entry: EntryId) : NavKey

/**
 * The sheet [EntryTrackSheetRoute] opens, with its pickers on a navigator of its own, as upstream's track
 * dialog has. Each page gets its own ViewModel store, or every picker would reuse the first one's model.
 */
@Composable
fun EntryTrackSheet(entry: EntryId, onDismissRequest: () -> Unit) {
    val sheetNavigator = rememberNavigator(EntryTrackInfoDialogHomeRoute(entry))

    CompositionLocalProvider(LocalNavigator provides sheetNavigator) {
        AdaptiveSheet(
            onDismissRequest = onDismissRequest,
            enableImplicitDismiss = !sheetNavigator.canPop,
        ) {
            NavigatorDisplay(
                navigator = sheetNavigator,
                sizeTransform = SizeTransform(),
                transitionSpec = { sheetFade },
                popTransitionSpec = { sheetFade },
                predictivePopTransitionSpec = { sheetFade },
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<EntryTrackInfoDialogHomeRoute> { EntryTrackInfoDialogHomeScreen(it.entry) }
                    entry<EntryTrackStatusSelectorRoute> { EntryTrackStatusSelectorScreen(it.serviceId, it.entry) }
                    entry<EntryTrackChapterSelectorRoute> { EntryTrackChapterSelectorScreen(it.serviceId, it.entry) }
                    entry<EntryTrackScoreSelectorRoute> { EntryTrackScoreSelectorScreen(it.serviceId, it.entry) }
                    entry<EntryTrackDateSelectorRoute> {
                        EntryTrackDateSelectorScreen(it.serviceId, it.start, it.entry)
                    }
                    entry<EntryTrackDateRemoverRoute> {
                        EntryTrackDateRemoverScreen(it.serviceId, it.start, it.entry)
                    }
                    entry<EntryTrackerSearchRoute> { EntryTrackerSearchScreen(it.entry, it.serviceId) }
                    entry<EntryTrackerRemoveRoute> { EntryTrackerRemoveScreen(it.entry, it.serviceId) }
                },
            )
        }
    }
}

private val sheetFade = fadeIn(tween(220, delayMillis = 90)) togetherWith fadeOut(tween(90))
