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
import eu.kanade.domain.track.interactor.RefreshTracks
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
import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import eu.kanade.tachiyomi.util.lang.convertEpochMillisZone
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.Flow
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
import reikai.domain.manga.DeleteTrackInGroup
import reikai.domain.manga.GetTracksInGroup
import reikai.domain.novel.interactor.AddNovelTrack
import reikai.domain.novel.interactor.DeleteNovelTrack
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.RefreshNovelTracks
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.track.NovelTrackUpdater
import reikai.domain.novel.track.toUiTrack
import reikai.domain.track.supportingContent
import reikai.domain.track.trackWriterFor
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
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
 * Five things branch on [isNovel]: the track subscription, the manga-only source-accept filter, the
 * search endpoint, the bind target and the delete scope. Only manga narrows by source, because
 * [EnhancedTracker] is a manga-only slot; a tracker serving novels from one source would need that
 * gate before it could declare supportsNovels, or it would be offered on every novel.
 */
data class EntryTrackInfoDialogHomeScreen(
    private val entryId: Long,
    private val entryTitle: String,
    private val sourceId: Long?,
    private val isNovel: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(entryId = entryId, sourceId = sourceId, isNovel = isNovel)
        }

        val dateFormat = remember { UiPreferences.dateFormat(context.appGraph.uiPreferences.dateFormat.get()) }
        val state by viewModel.state.collectAsState()

        TrackInfoDialogHome(
            trackItems = state.trackItems,
            dateFormat = dateFormat,
            onStatusClick = {
                navigator.push(EntryTrackStatusSelectorScreen(it.track!!, it.tracker.id, isNovel))
            },
            onChapterClick = {
                navigator.push(EntryTrackChapterSelectorScreen(it.track!!, it.tracker.id, isNovel))
            },
            onScoreClick = {
                navigator.push(EntryTrackScoreSelectorScreen(it.track!!, it.tracker.id, isNovel))
            },
            onStartDateEdit = {
                navigator.push(EntryTrackDateSelectorScreen(it.track!!, it.tracker.id, start = true, isNovel))
            },
            onEndDateEdit = {
                navigator.push(EntryTrackDateSelectorScreen(it.track!!, it.tracker.id, start = false, isNovel))
            },
            onNewSearch = {
                if (!isNovel && it.tracker is EnhancedTracker) {
                    viewModel.registerEnhancedTracking(it)
                } else {
                    navigator.push(
                        EntryTrackerSearchScreen(
                            entryId = entryId,
                            initialQuery = it.track?.title ?: entryTitle,
                            currentUrl = it.track?.remoteUrl,
                            serviceId = it.tracker.id,
                            isNovel = isNovel,
                        ),
                    )
                }
            },
            onOpenInBrowser = { openTrackerInBrowser(context, it) },
            onRemoved = {
                navigator.push(EntryTrackerRemoveScreen(entryId, it.track!!, it.tracker.id, isNovel))
            },
            onCopyLink = { context.copyTrackerLink(it) },
            onTogglePrivate = viewModel::togglePrivate,
        )
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
        @Assisted private val entryId: Long,
        @Assisted private val sourceId: Long?,
        @Assisted private val isNovel: Boolean,
        private val context: Context,
        private val getTracksInGroup: GetTracksInGroup,
        private val getNovelTracks: GetNovelTracks,
        private val getManga: GetManga,
        private val trackerManager: TrackerManager,
        private val sourceManager: SourceManager,
        private val refreshTracks: RefreshTracks,
        private val refreshNovelTracks: RefreshNovelTracks,
        novelTrackUpdater: NovelTrackUpdater,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State())

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(entryId: Long, sourceId: Long?, isNovel: Boolean): Model
        }

        private val writer = trackWriterFor(isNovel, novelTrackUpdater)

        init {
            viewModelScope.launch { refreshTrackers() }

            viewModelScope.launch {
                entryTrackFlow()
                    .catch { logcat(LogPriority.ERROR, it) }
                    .distinctUntilChanged()
                    .map { it.mapToTrackItem() }
                    .collectLatest { trackItems -> state.update { it.copy(trackItems = trackItems) } }
            }
        }

        private fun entryTrackFlow(): Flow<List<Track>> =
            if (isNovel) {
                // Both reads span the merge group, so a track bound on a sibling source shows here.
                getNovelTracks.subscribeGroup(entryId).map { tracks -> tracks.map(NovelTrack::toUiTrack) }
            } else {
                getTracksInGroup.subscribe(entryId)
            }

        // Manga-only: EnhancedTracker matches a manga to its same-id remote entry with no manual search.
        fun registerEnhancedTracking(item: TrackItem) {
            item.tracker as EnhancedTracker
            viewModelScope.launchNonCancellable {
                val manga = getManga.await(entryId) ?: return@launchNonCancellable
                try {
                    val matchResult = item.tracker.match(manga) ?: throw Exception()
                    item.tracker.register(matchResult, entryId)
                } catch (_: Exception) {
                    withUIContext { context.toast(MR.strings.error_no_match) }
                }
            }
        }

        private suspend fun refreshTrackers() {
            val results = if (isNovel) {
                refreshNovelTracks.await(entryId)
            } else {
                refreshTracks.await(entryId)
            }
            results
                .filter { it.first != null }
                .forEach { (track, e) ->
                    logcat(LogPriority.ERROR, e) {
                        "Failed to refresh track data entryId=$entryId for service ${track!!.id}"
                    }
                    withUIContext {
                        context.toast(context.stringResource(MR.strings.track_error, track!!.name, e.message ?: ""))
                    }
                }
        }

        fun togglePrivate(item: TrackItem) {
            viewModelScope.launchNonCancellable {
                writer.setRemotePrivate(item.tracker, item.track!!.toDbTrack(), !item.track.private)
            }
        }

        private suspend fun List<Track>.mapToTrackItem(): List<TrackItem> {
            // Only trackers whose catalogue holds this type; the rest would silently bind the other's hit.
            val loggedInTrackers = trackerManager.loggedInTrackers().supportingContent(isNovel)
            return if (isNovel) {
                loggedInTrackers.map { service -> TrackItem(find { it.trackerId == service.id }, service) }
            } else {
                val source = sourceManager.getOrStub(sourceId!!)
                loggedInTrackers
                    .map { service -> TrackItem(find { it.trackerId == service.id }, service) }
                    // Show only if the service supports this manga's source
                    .filter { (it.tracker as? EnhancedTracker)?.accept(source) ?: true }
            }
        }

        @Immutable
        data class State(
            val trackItems: List<TrackItem> = emptyList(),
        )
    }
}

data class EntryTrackStatusSelectorScreen(
    private val track: Track,
    private val serviceId: Long,
    private val isNovel: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(track = track, trackerId = serviceId, isNovel = isNovel)
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
        @Assisted isNovel: Boolean,
        trackerManager: TrackerManager,
        novelTrackUpdater: NovelTrackUpdater,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State(track.status))

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long, isNovel: Boolean): Model
        }

        // The tracker and the writer are derived from the ids rather than passed in, so the dialog
        // stops resolving DI from a composable body. Every selector model below does the same.
        private val tracker = trackerManager.get(trackerId)!!
        private val writer = trackWriterFor(isNovel, novelTrackUpdater)

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
    private val isNovel: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(track = track, trackerId = serviceId, isNovel = isNovel)
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
        @Assisted isNovel: Boolean,
        trackerManager: TrackerManager,
        novelTrackUpdater: NovelTrackUpdater,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State(track.lastChapterRead.toInt()))

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long, isNovel: Boolean): Model
        }

        private val tracker = trackerManager.get(trackerId)!!
        private val writer = trackWriterFor(isNovel, novelTrackUpdater)

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
    private val isNovel: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(track = track, trackerId = serviceId, isNovel = isNovel)
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
        @Assisted isNovel: Boolean,
        trackerManager: TrackerManager,
        novelTrackUpdater: NovelTrackUpdater,
    ) : ViewModel() {

        // Declared above the state, which seeds itself from the tracker: property initializers run in
        // declaration order, so the reverse order would read an unset tracker.
        private val tracker = trackerManager.get(trackerId)!!
        private val writer = trackWriterFor(isNovel, novelTrackUpdater)

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State(tracker.displayScore(track)))

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long, isNovel: Boolean): Model
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
    private val isNovel: Boolean,
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
            create(track = track, trackerId = serviceId, start = start, isNovel = isNovel)
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
        @Assisted private val isNovel: Boolean,
        trackerManager: TrackerManager,
        novelTrackUpdater: NovelTrackUpdater,
    ) : ViewModel() {

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long, start: Boolean, isNovel: Boolean): Model
        }

        private val tracker = trackerManager.get(trackerId)!!
        private val writer = trackWriterFor(isNovel, novelTrackUpdater)

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
            navigator.push(EntryTrackDateRemoverScreen(track, tracker.id, start, isNovel))
        }
    }
}

data class EntryTrackDateRemoverScreen(
    private val track: Track,
    private val serviceId: Long,
    private val start: Boolean,
    private val isNovel: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(track = track, trackerId = serviceId, start = start, isNovel = isNovel)
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
        @Assisted isNovel: Boolean,
        trackerManager: TrackerManager,
        novelTrackUpdater: NovelTrackUpdater,
    ) : ViewModel() {

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(track: Track, trackerId: Long, start: Boolean, isNovel: Boolean): Model
        }

        private val tracker = trackerManager.get(trackerId)!!
        private val writer = trackWriterFor(isNovel, novelTrackUpdater)

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
    private val entryId: Long,
    private val initialQuery: String,
    private val currentUrl: String?,
    private val serviceId: Long,
    private val isNovel: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(
                entryId = entryId,
                currentUrl = currentUrl,
                initialQuery = initialQuery,
                trackerId = serviceId,
                isNovel = isNovel,
            )
        }

        val state by viewModel.state.collectAsState()

        val textFieldState = rememberTextFieldState(initialQuery)
        TrackerSearch(
            state = textFieldState,
            onDispatchQuery = { viewModel.trackingSearch(textFieldState.text.toString()) },
            queryResult = state.queryResult,
            selected = state.selected,
            onSelectedChange = viewModel::updateSelection,
            onConfirmSelection = f@{ private: Boolean ->
                val selected = state.selected ?: return@f
                selected.private = private
                viewModel.registerTracking(selected)
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
            supportsPrivateTracking = viewModel.supportsPrivateTracking,
        )
    }

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val entryId: Long,
        @Assisted private val currentUrl: String?,
        @Assisted initialQuery: String,
        @Assisted trackerId: Long,
        @Assisted private val isNovel: Boolean,
        private val addNovelTrack: AddNovelTrack,
        trackerManager: TrackerManager,
    ) : ViewModel() {

        val state: StateFlow<Model.State>
            field = MutableStateFlow<Model.State>(State())

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(
                entryId: Long,
                currentUrl: String?,
                initialQuery: String,
                trackerId: Long,
                isNovel: Boolean,
            ): Model
        }

        private val tracker = trackerManager.get(trackerId)!!

        val supportsPrivateTracking = tracker.supportsPrivateTracking

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
                        // Novels have their own catalogue on some trackers (e.g. a separate endpoint).
                        val results = if (isNovel) tracker.searchNovel(query) else tracker.search(query)
                        Result.success(results)
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
                if (isNovel) {
                    addNovelTrack.bind(tracker, item, entryId)
                } else {
                    tracker.register(item, entryId)
                }
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
    private val entryId: Long,
    private val track: Track,
    private val serviceId: Long,
    private val isNovel: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<Model, Model.Factory> {
            create(entryId = entryId, track = track, trackerId = serviceId, isNovel = isNovel)
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
        @Assisted private val entryId: Long,
        @Assisted private val track: Track,
        @Assisted trackerId: Long,
        @Assisted private val isNovel: Boolean,
        private val deleteNovelTrack: DeleteNovelTrack,
        private val deleteTrackInGroup: DeleteTrackInGroup,
        trackerManager: TrackerManager,
    ) : ViewModel() {

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(entryId: Long, track: Track, trackerId: Long, isNovel: Boolean): Model
        }

        private val tracker = trackerManager.get(trackerId)!!

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
                // Group-aware on both types: clear the tracker from every merged source, so a sibling's
                // row can't keep it alive in the library's tracker filter, sort and grouping.
                if (isNovel) {
                    deleteNovelTrack.awaitGroup(entryId, serviceId)
                } else {
                    deleteTrackInGroup.await(entryId, serviceId)
                }
            }
        }
    }
}
