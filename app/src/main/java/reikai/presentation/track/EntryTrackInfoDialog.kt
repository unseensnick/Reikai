package reikai.presentation.track

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
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
import eu.kanade.presentation.track.TrackChapterSelector
import eu.kanade.presentation.track.TrackDateSelector
import eu.kanade.presentation.track.TrackInfoDialogHome
import eu.kanade.presentation.track.TrackScoreSelector
import eu.kanade.presentation.track.TrackStatusSelector
import eu.kanade.presentation.track.TrackerSearch
import eu.kanade.presentation.util.Screen
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import logcat.LogPriority
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Warning
import reikai.domain.entry.EntryId
import reikai.domain.track.EntryTrackPort
import reikai.domain.track.EntryTrackPorts
import reikai.domain.track.autobind.AutoBindTracker
import reikai.domain.track.autobind.AutoBindTrackers
import reikai.domain.track.autobind.offerTrackers
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.AlertDialogContent
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The single track-info dialog stack for both manga and novels: the same domain [Track] (novels adapt
 * via [reikai.domain.novel.track.toUiTrack]) written through a [TrackWriter], so the two cannot drift.
 * Whatever is engine-specific goes through the entry's [EntryTrackPort], which [EntryTrackPorts] picks
 * from the [EntryId] once, so no screen here branches on the content type.
 * A tracker that binds entries from a source it knows ([AutoBindTracker]) matches on a tap instead of
 * searching for those entries; [offerTrackers] decides the rows, for both types.
 */
data class EntryTrackInfoDialogHomeScreen(
    private val entry: EntryId,
    private val entryTitle: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(entry = entry)
        }

        val dateFormat = remember { UiPreferences.dateFormat(context.appGraph.uiPreferences.dateFormat.get()) }
        val state by viewModel.state.collectAsState()
        var pendingAutoBind by remember { mutableStateOf<TrackItem?>(null) }

        TrackInfoDialogHome(
            trackItems = state.trackItems,
            dateFormat = dateFormat,
            onStatusClick = {
                navigator.push(EntryTrackStatusSelectorScreen(it.track!!, it.tracker.id, entry))
            },
            onChapterClick = {
                navigator.push(EntryTrackChapterSelectorScreen(it.track!!, it.tracker.id, entry))
            },
            onScoreClick = {
                navigator.push(EntryTrackScoreSelectorScreen(it.track!!, it.tracker.id, entry))
            },
            onStartDateEdit = {
                navigator.push(EntryTrackDateSelectorScreen(it.track!!, it.tracker.id, start = true, entry))
            },
            onEndDateEdit = {
                navigator.push(EntryTrackDateSelectorScreen(it.track!!, it.tracker.id, start = false, entry))
            },
            onNewSearch = {
                if (it.tracker.id in state.autoMatchTrackerIds) {
                    if (it.tracker is ReplacingWriteTracker) pendingAutoBind = it else viewModel.registerAutoBind(it)
                } else {
                    navigator.push(
                        EntryTrackerSearchScreen(
                            entry = entry,
                            initialQuery = it.track?.title ?: entryTitle,
                            currentUrl = it.track?.remoteUrl,
                            serviceId = it.tracker.id,
                        ),
                    )
                }
            },
            onOpenInBrowser = { openTrackerInBrowser(context, it) },
            onRemoved = {
                navigator.push(EntryTrackerRemoveScreen(entry, it.track!!, it.tracker.id))
            },
            onCopyLink = { context.copyTrackerLink(it) },
            onTogglePrivate = viewModel::togglePrivate,
        )

        pendingAutoBind?.let { item ->
            ReplaceEntryConfirmDialog(
                trackerName = item.tracker.name,
                onConfirm = {
                    pendingAutoBind = null
                    viewModel.registerAutoBind(item)
                },
                onDismissRequest = { pendingAutoBind = null },
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

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val entry: EntryId,
        private val context: Context,
        private val trackerManager: TrackerManager,
        private val autoBindTrackers: AutoBindTrackers,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State())

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
            viewModelScope.launchNonCancellable {
                val bindEntry = port.autoBindEntry() ?: return@launchNonCancellable
                val match = try {
                    candidate.match(bindEntry)
                } catch (_: Exception) {
                    null
                }
                if (match == null) {
                    withUIContext { context.toast(MR.strings.error_no_match) }
                    return@launchNonCancellable
                }
                bindTrack(context, port, item.tracker, match)
            }
        }

        private suspend fun refreshTrackers() {
            port.refresh()
                .filter { it.first != null }
                .forEach { (track, e) ->
                    logcat(LogPriority.ERROR, e) {
                        "Failed to refresh track data entry=$entry for service ${track!!.id}"
                    }
                    withUIContext {
                        context.toast(context.stringResource(MR.strings.track_error, track!!.name, e.message ?: ""))
                    }
                }
        }

        fun togglePrivate(item: TrackItem) {
            viewModelScope.launchNonCancellable {
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

data class EntryTrackStatusSelectorScreen(
    private val track: Track,
    private val serviceId: Long,
    private val entry: EntryId,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(track = track, trackerId = serviceId, entry = entry)
        }
        val state by viewModel.state.collectAsState()
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

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val track: Track,
        @Assisted trackerId: Long,
        @Assisted entry: EntryId,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State(track.status))

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long, entry: EntryId): Model
        }

        // The tracker and the writer are derived from the ids rather than passed in, so the dialog
        // stops resolving DI from a composable body. Every selector model below does the same.
        private val tracker = trackerManager.get(trackerId)!!
        private val writer = ports.of(entry).writer

        fun getSelections(): Map<Long, StringResource?> =
            tracker.getStatusList().associateWith { tracker.getStatus(it) }

        fun setSelection(selection: Long) = state.update { it.copy(selection = selection) }

        fun setStatus() {
            viewModelScope.launchNonCancellable {
                writer.setRemoteStatus(tracker, track.toDbTrack(), state.value.selection)
            }
        }

        @Immutable
        data class State(val selection: Long)
    }
}

data class EntryTrackChapterSelectorScreen(
    private val track: Track,
    private val serviceId: Long,
    private val entry: EntryId,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(track = track, trackerId = serviceId, entry = entry)
        }
        val state by viewModel.state.collectAsState()
        TrackChapterSelector(
            selection = state.selection,
            onSelectionChange = viewModel::setSelection,
            range = remember { viewModel.getRange() },
            onConfirm = {
                viewModel.setChapter()
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
        )
    }

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val track: Track,
        @Assisted trackerId: Long,
        @Assisted entry: EntryId,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State(track.lastChapterRead.toInt()))

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long, entry: EntryId): Model
        }

        private val tracker = trackerManager.get(trackerId)!!
        private val writer = ports.of(entry).writer

        fun getRange(): Iterable<Int> {
            val endRange = if (track.totalChapters > 0) track.totalChapters else 10000
            return 0..endRange.toInt()
        }

        fun setSelection(selection: Int) = state.update { it.copy(selection = selection) }

        fun setChapter() {
            viewModelScope.launchNonCancellable {
                writer.setRemoteLastChapterRead(tracker, track.toDbTrack(), state.value.selection)
            }
        }

        @Immutable
        data class State(val selection: Int)
    }
}

data class EntryTrackScoreSelectorScreen(
    private val track: Track,
    private val serviceId: Long,
    private val entry: EntryId,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(track = track, trackerId = serviceId, entry = entry)
        }
        val state by viewModel.state.collectAsState()
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

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val track: Track,
        @Assisted trackerId: Long,
        @Assisted entry: EntryId,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        // Declared above the state, which seeds itself from the tracker: property initializers run in
        // declaration order, so the reverse order would read an unset tracker.
        private val tracker = trackerManager.get(trackerId)!!
        private val writer = ports.of(entry).writer

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State(tracker.displayScore(track)))

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long, entry: EntryId): Model
        }

        fun getSelections(): List<String> = tracker.getScoreList()

        fun setSelection(selection: String) = state.update { it.copy(selection = selection) }

        fun setScore() {
            viewModelScope.launchNonCancellable {
                writer.setRemoteScore(tracker, track.toDbTrack(), state.value.selection)
            }
        }

        @Immutable
        data class State(val selection: String)
    }
}

data class EntryTrackDateSelectorScreen(
    private val track: Track,
    private val serviceId: Long,
    private val start: Boolean,
    private val entry: EntryId,
) : Screen() {

    @Transient
    private val selectableDates = object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
            val targetDate = Instant.fromEpochMilliseconds(utcTimeMillis).toLocalDateTime(TimeZone.UTC)

            // Disallow future dates
            if (targetDate > Clock.System.now().toLocalDateTime(TimeZone.UTC)) return false

            return when {
                // Disallow setting start date after finish date
                start && track.finishDate > 0 -> {
                    val finishDate = Instant.fromEpochMilliseconds(track.finishDate).toLocalDateTime(TimeZone.UTC)
                    targetDate <= finishDate
                }
                // Disallow setting finish date before start date
                !start && track.startDate > 0 -> {
                    val startDate = Instant.fromEpochMilliseconds(track.startDate).toLocalDateTime(TimeZone.UTC)
                    startDate <= targetDate
                }
                else -> true
            }
        }

        override fun isSelectableYear(year: Int): Boolean {
            // Disallow future years
            if (year > Clock.System.now().toLocalDateTime(TimeZone.UTC).year) return false

            return when {
                // Disallow setting start year after finish year
                start && track.finishDate > 0 -> {
                    val finishDate = Instant.fromEpochMilliseconds(track.finishDate).toLocalDateTime(TimeZone.UTC)
                    year <= finishDate.year
                }
                // Disallow setting finish year before start year
                !start && track.startDate > 0 -> {
                    val startDate = Instant.fromEpochMilliseconds(track.startDate).toLocalDateTime(TimeZone.UTC)
                    startDate.year <= year
                }
                else -> true
            }
        }
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(track = track, trackerId = serviceId, start = start, entry = entry)
        }

        val canRemove = if (start) track.startDate > 0 else track.finishDate > 0
        TrackDateSelector(
            title = if (start) {
                stringResource(MR.strings.track_started_reading_date)
            } else {
                stringResource(MR.strings.track_finished_reading_date)
            },
            initialSelectedDateMillis = viewModel.initialSelection,
            selectableDates = selectableDates,
            onConfirm = {
                viewModel.setDate(it)
                navigator.pop()
            },
            onRemove = { viewModel.confirmRemoveDate(navigator) }.takeIf { canRemove },
            onDismissRequest = navigator::pop,
        )
    }

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val track: Track,
        @Assisted trackerId: Long,
        @Assisted private val start: Boolean,
        @Assisted private val entry: EntryId,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long, start: Boolean, entry: EntryId): Model
        }

        private val tracker = trackerManager.get(trackerId)!!
        private val writer = ports.of(entry).writer

        // In UTC
        val initialSelection: Long
            get() {
                val millis = (if (start) track.startDate else track.finishDate)
                    .takeIf { it != 0L }
                    ?: Clock.System.now().toEpochMilliseconds()
                return millis.convertEpochMillisZone(TimeZone.currentSystemDefault(), TimeZone.UTC)
            }

        // In UTC
        fun setDate(millis: Long) {
            // Convert to local time
            val localMillis = millis.convertEpochMillisZone(TimeZone.UTC, TimeZone.currentSystemDefault())
            viewModelScope.launchNonCancellable {
                if (start) {
                    writer.setRemoteStartDate(tracker, track.toDbTrack(), localMillis)
                } else {
                    writer.setRemoteFinishDate(tracker, track.toDbTrack(), localMillis)
                }
            }
        }

        fun confirmRemoveDate(navigator: Navigator) {
            navigator.push(EntryTrackDateRemoverScreen(track, tracker.id, start, entry))
        }
    }
}

data class EntryTrackDateRemoverScreen(
    private val track: Track,
    private val serviceId: Long,
    private val start: Boolean,
    private val entry: EntryId,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(track = track, trackerId = serviceId, start = start, entry = entry)
        }
        AlertDialogContent(
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
            icon = { Icon(imageVector = MaterialSymbols.Rounded.Delete, contentDescription = null) },
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
                            navigator.popUntil { it is EntryTrackInfoDialogHomeScreen }
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

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val track: Track,
        @Assisted trackerId: Long,
        @Assisted private val start: Boolean,
        @Assisted entry: EntryId,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long, start: Boolean, entry: EntryId): Model
        }

        private val tracker = trackerManager.get(trackerId)!!
        private val writer = ports.of(entry).writer

        fun getServiceName() = tracker.name

        fun removeDate() {
            viewModelScope.launchNonCancellable {
                if (start) {
                    writer.setRemoteStartDate(tracker, track.toDbTrack(), 0)
                } else {
                    writer.setRemoteFinishDate(tracker, track.toDbTrack(), 0)
                }
            }
        }
    }
}

data class EntryTrackerSearchScreen(
    private val entry: EntryId,
    private val initialQuery: String,
    private val currentUrl: String?,
    private val serviceId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(
                entry = entry,
                currentUrl = currentUrl,
                initialQuery = initialQuery,
                trackerId = serviceId,
            )
        }

        val state by viewModel.state.collectAsState()

        val textFieldState = rememberTextFieldState(initialQuery)
        var pendingBind by remember { mutableStateOf<TrackSearch?>(null) }
        TrackerSearch(
            state = textFieldState,
            onDispatchQuery = { viewModel.trackingSearch(textFieldState.text.toString()) },
            queryResult = state.queryResult,
            selected = state.selected,
            onSelectedChange = viewModel::updateSelection,
            onConfirmSelection = f@{ private: Boolean ->
                val selected = state.selected ?: return@f
                selected.private = private
                if (viewModel.replacesRemoteEntry) {
                    pendingBind = selected
                } else {
                    viewModel.registerTracking(selected)
                    navigator.pop()
                }
            },
            onDismissRequest = navigator::pop,
            supportsPrivateTracking = viewModel.supportsPrivateTracking,
        )

        pendingBind?.let { selected ->
            ReplaceEntryConfirmDialog(
                trackerName = viewModel.trackerName,
                onConfirm = {
                    pendingBind = null
                    viewModel.registerTracking(selected)
                    navigator.pop()
                },
                onDismissRequest = { pendingBind = null },
            )
        }
    }

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted entry: EntryId,
        @Assisted private val currentUrl: String?,
        @Assisted initialQuery: String,
        @Assisted trackerId: Long,
        private val context: Context,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State())

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(
                entry: EntryId,
                currentUrl: String?,
                initialQuery: String,
                trackerId: Long,
            ): Model
        }

        private val tracker = trackerManager.get(trackerId)!!
        private val port = ports.of(entry)

        val supportsPrivateTracking = tracker.supportsPrivateTracking

        val replacesRemoteEntry = tracker is ReplacingWriteTracker

        val trackerName = tracker.name

        init {
            // Run search on first launch
            if (initialQuery.isNotBlank()) {
                trackingSearch(initialQuery)
            }
        }

        fun trackingSearch(query: String) {
            viewModelScope.launch {
                // To show loading state
                state.update { it.copy(queryResult = null, selected = null) }

                val result = withIOContext {
                    try {
                        Result.success(port.search(tracker, query))
                    } catch (e: Throwable) {
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
            viewModelScope.launchNonCancellable {
                bindTrack(context, port, tracker, item)
            }
        }

        fun updateSelection(selected: TrackSearch) = state.update { it.copy(selected = selected) }

        @Immutable
        data class State(
            val queryResult: Result<List<TrackSearch>>? = null,
            val selected: TrackSearch? = null,
        )
    }
}

data class EntryTrackerRemoveScreen(
    private val entry: EntryId,
    private val track: Track,
    private val serviceId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(entry = entry, track = track, trackerId = serviceId)
        }
        val serviceName = viewModel.getName()
        var removeRemoteTrack by remember { mutableStateOf(false) }
        AlertDialogContent(
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
            icon = { Icon(imageVector = MaterialSymbols.Rounded.Delete, contentDescription = null) },
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
                            viewModel.unregisterTracking(serviceId)
                            if (removeRemoteTrack) viewModel.deleteEntryFromService()
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

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted entry: EntryId,
        @Assisted private val track: Track,
        @Assisted trackerId: Long,
        trackerManager: TrackerManager,
        ports: EntryTrackPorts,
    ) : ViewModel() {

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(entry: EntryId, track: Track, trackerId: Long): Model
        }

        private val tracker = trackerManager.get(trackerId)!!
        private val port = ports.of(entry)

        fun getName() = tracker.name

        fun isDeletable() = tracker is DeletableTracker

        fun deleteEntryFromService() {
            viewModelScope.launchNonCancellable {
                try {
                    (tracker as DeletableTracker).delete(track)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to delete entry from service" }
                }
            }
        }

        fun unregisterTracking(serviceId: Long) {
            viewModelScope.launchNonCancellable {
                // Cleared from every merged source, so a sibling's row can't keep the tracker alive in
                // the library's tracker filter, sort and grouping.
                port.unbindInGroup(serviceId)
            }
        }
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
        icon = { Icon(imageVector = MaterialSymbols.Rounded.Warning, contentDescription = null) },
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
        withUIContext { context.toast(context.trackerErrorMessage(tracker, e)) }
    }
}
