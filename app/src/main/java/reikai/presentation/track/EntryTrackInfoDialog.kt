package reikai.presentation.track

import android.app.Application
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
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
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
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
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import eu.kanade.tachiyomi.util.lang.convertEpochMillisZone
import eu.kanade.tachiyomi.util.lang.toLocalDate
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.domain.manga.PropagateTrackerLinks
import reikai.domain.novel.interactor.AddNovelTrack
import reikai.domain.novel.interactor.DeleteNovelTrack
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.RefreshNovelTracks
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.track.toUiTrack
import reikai.domain.track.TrackWriter
import reikai.domain.track.trackWriterFor
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.DeleteTrack
import tachiyomi.domain.track.interactor.GetTracks
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.AlertDialogContent
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The single track-info dialog stack for both manga and novels. Both content types carry the same domain
 * [Track] (novels adapt via [reikai.domain.novel.track.toUiTrack]) and write through a [TrackWriter], so the
 * two catalogues cannot drift. Only five things branch on [isNovel]: the track subscription (single-id vs
 * merge-group), the tracker filter (source-accept vs supportsNovels), the search endpoint (search vs
 * searchNovel), the bind target (tracker.register + group propagate vs AddNovelTrack), and the delete scope
 * (single-id vs group). The [EnhancedTracker] auto-bind is a manga-only slot (no novel tracker is enhanced).
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
        val screenModel = rememberScreenModel { Model(entryId, sourceId, isNovel) }

        val dateFormat = remember { UiPreferences.dateFormat(Injekt.get<UiPreferences>().dateFormat.get()) }
        val state by screenModel.state.collectAsState()

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
                    screenModel.registerEnhancedTracking(it)
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
            onTogglePrivate = screenModel::togglePrivate,
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

    private class Model(
        private val entryId: Long,
        private val sourceId: Long?,
        private val isNovel: Boolean,
        private val getTracks: GetTracks = Injekt.get(),
        private val getNovelTracks: GetNovelTracks = Injekt.get(),
    ) : StateScreenModel<Model.State>(State()) {

        private val writer: TrackWriter = trackWriterFor(isNovel)

        init {
            screenModelScope.launch { refreshTrackers() }

            screenModelScope.launch {
                entryTrackFlow()
                    .catch { logcat(LogPriority.ERROR, it) }
                    .distinctUntilChanged()
                    .map { it.mapToTrackItem() }
                    .collectLatest { trackItems -> mutableState.update { it.copy(trackItems = trackItems) } }
            }
        }

        private fun entryTrackFlow(): Flow<List<Track>> =
            if (isNovel) {
                // subscribeGroup spans the merge group, so a track bound on a sibling source shows here.
                getNovelTracks.subscribeGroup(entryId).map { tracks -> tracks.map(NovelTrack::toUiTrack) }
            } else {
                getTracks.subscribe(entryId)
            }

        // Manga-only: EnhancedTracker matches a manga to its same-id remote entry with no manual search.
        fun registerEnhancedTracking(item: TrackItem) {
            item.tracker as EnhancedTracker
            screenModelScope.launchNonCancellable {
                val manga = Injekt.get<GetManga>().await(entryId) ?: return@launchNonCancellable
                try {
                    val matchResult = item.tracker.match(manga) ?: throw Exception()
                    item.tracker.register(matchResult, entryId)
                    // RK: share the new tracker with the rest of the merged group
                    Injekt.get<PropagateTrackerLinks>().fromSeed(entryId)
                } catch (_: Exception) {
                    withUIContext { Injekt.get<Application>().toast(MR.strings.error_no_match) }
                }
            }
        }

        private suspend fun refreshTrackers() {
            val context = Injekt.get<Application>()
            val results = if (isNovel) {
                Injekt.get<RefreshNovelTracks>().await(entryId)
            } else {
                Injekt.get<RefreshTracks>().await(entryId)
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
            screenModelScope.launchNonCancellable {
                writer.setRemotePrivate(item.tracker, item.track!!.toDbTrack(), !item.track.private)
            }
        }

        private fun List<Track>.mapToTrackItem(): List<TrackItem> {
            val loggedInTrackers = Injekt.get<TrackerManager>().loggedInTrackers()
            return if (isNovel) {
                // Only trackers with a real novel search; the rest would silently bind a manga hit.
                loggedInTrackers
                    .filter { it.supportsNovels }
                    .map { service -> TrackItem(find { it.trackerId == service.id }, service) }
            } else {
                val source = Injekt.get<SourceManager>().getOrStub(sourceId!!)
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

private data class EntryTrackStatusSelectorScreen(
    private val track: Track,
    private val serviceId: Long,
    private val isNovel: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            Model(track, Injekt.get<TrackerManager>().get(serviceId)!!, trackWriterFor(isNovel))
        }
        val state by screenModel.state.collectAsState()
        TrackStatusSelector(
            selection = state.selection,
            onSelectionChange = screenModel::setSelection,
            selections = remember { screenModel.getSelections() },
            onConfirm = {
                screenModel.setStatus()
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
        )
    }

    private class Model(
        private val track: Track,
        private val tracker: Tracker,
        private val writer: TrackWriter,
    ) : StateScreenModel<Model.State>(State(track.status)) {

        fun getSelections(): Map<Long, StringResource?> =
            tracker.getStatusList().associateWith { tracker.getStatus(it) }

        fun setSelection(selection: Long) = mutableState.update { it.copy(selection = selection) }

        fun setStatus() {
            screenModelScope.launchNonCancellable {
                writer.setRemoteStatus(tracker, track.toDbTrack(), state.value.selection)
            }
        }

        @Immutable
        data class State(val selection: Long)
    }
}

private data class EntryTrackChapterSelectorScreen(
    private val track: Track,
    private val serviceId: Long,
    private val isNovel: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            Model(track, Injekt.get<TrackerManager>().get(serviceId)!!, trackWriterFor(isNovel))
        }
        val state by screenModel.state.collectAsState()
        TrackChapterSelector(
            selection = state.selection,
            onSelectionChange = screenModel::setSelection,
            range = remember { screenModel.getRange() },
            onConfirm = {
                screenModel.setChapter()
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
        )
    }

    private class Model(
        private val track: Track,
        private val tracker: Tracker,
        private val writer: TrackWriter,
    ) : StateScreenModel<Model.State>(State(track.lastChapterRead.toInt())) {

        fun getRange(): Iterable<Int> {
            val endRange = if (track.totalChapters > 0) track.totalChapters else 10000
            return 0..endRange.toInt()
        }

        fun setSelection(selection: Int) = mutableState.update { it.copy(selection = selection) }

        fun setChapter() {
            screenModelScope.launchNonCancellable {
                writer.setRemoteLastChapterRead(tracker, track.toDbTrack(), state.value.selection)
            }
        }

        @Immutable
        data class State(val selection: Int)
    }
}

private data class EntryTrackScoreSelectorScreen(
    private val track: Track,
    private val serviceId: Long,
    private val isNovel: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            Model(track, Injekt.get<TrackerManager>().get(serviceId)!!, trackWriterFor(isNovel))
        }
        val state by screenModel.state.collectAsState()
        TrackScoreSelector(
            selection = state.selection,
            onSelectionChange = screenModel::setSelection,
            selections = remember { screenModel.getSelections() },
            onConfirm = {
                screenModel.setScore()
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
        )
    }

    private class Model(
        private val track: Track,
        private val tracker: Tracker,
        private val writer: TrackWriter,
    ) : StateScreenModel<Model.State>(State(tracker.displayScore(track))) {

        fun getSelections(): List<String> = tracker.getScoreList()

        fun setSelection(selection: String) = mutableState.update { it.copy(selection = selection) }

        fun setScore() {
            screenModelScope.launchNonCancellable {
                writer.setRemoteScore(tracker, track.toDbTrack(), state.value.selection)
            }
        }

        @Immutable
        data class State(val selection: String)
    }
}

private data class EntryTrackDateSelectorScreen(
    private val track: Track,
    private val serviceId: Long,
    private val start: Boolean,
    private val isNovel: Boolean,
) : Screen() {

    @Transient
    private val selectableDates = object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
            val targetDate = Instant.ofEpochMilli(utcTimeMillis).toLocalDate(ZoneOffset.UTC)

            // Disallow future dates
            if (targetDate > LocalDate.now(ZoneOffset.UTC)) return false

            return when {
                // Disallow setting start date after finish date
                start && track.finishDate > 0 -> {
                    val finishDate = Instant.ofEpochMilli(track.finishDate).toLocalDate(ZoneOffset.UTC)
                    targetDate <= finishDate
                }
                // Disallow setting finish date before start date
                !start && track.startDate > 0 -> {
                    val startDate = Instant.ofEpochMilli(track.startDate).toLocalDate(ZoneOffset.UTC)
                    startDate <= targetDate
                }
                else -> true
            }
        }

        override fun isSelectableYear(year: Int): Boolean {
            // Disallow future years
            if (year > LocalDate.now(ZoneOffset.UTC).year) return false

            return when {
                // Disallow setting start year after finish year
                start && track.finishDate > 0 -> {
                    val finishDate = Instant.ofEpochMilli(track.finishDate).toLocalDate(ZoneOffset.UTC)
                    year <= finishDate.year
                }
                // Disallow setting finish year before start year
                !start && track.startDate > 0 -> {
                    val startDate = Instant.ofEpochMilli(track.startDate).toLocalDate(ZoneOffset.UTC)
                    startDate.year <= year
                }
                else -> true
            }
        }
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            Model(track, Injekt.get<TrackerManager>().get(serviceId)!!, start, trackWriterFor(isNovel), isNovel)
        }

        val canRemove = if (start) track.startDate > 0 else track.finishDate > 0
        TrackDateSelector(
            title = if (start) {
                stringResource(MR.strings.track_started_reading_date)
            } else {
                stringResource(MR.strings.track_finished_reading_date)
            },
            initialSelectedDateMillis = screenModel.initialSelection,
            selectableDates = selectableDates,
            onConfirm = {
                screenModel.setDate(it)
                navigator.pop()
            },
            onRemove = { screenModel.confirmRemoveDate(navigator) }.takeIf { canRemove },
            onDismissRequest = navigator::pop,
        )
    }

    private class Model(
        private val track: Track,
        private val tracker: Tracker,
        private val start: Boolean,
        private val writer: TrackWriter,
        private val isNovel: Boolean,
    ) : ScreenModel {

        // In UTC
        val initialSelection: Long
            get() {
                val millis = (if (start) track.startDate else track.finishDate)
                    .takeIf { it != 0L }
                    ?: Instant.now().toEpochMilli()
                return millis.convertEpochMillisZone(ZoneOffset.systemDefault(), ZoneOffset.UTC)
            }

        // In UTC
        fun setDate(millis: Long) {
            // Convert to local time
            val localMillis = millis.convertEpochMillisZone(ZoneOffset.UTC, ZoneOffset.systemDefault())
            screenModelScope.launchNonCancellable {
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

private data class EntryTrackDateRemoverScreen(
    private val track: Track,
    private val serviceId: Long,
    private val start: Boolean,
    private val isNovel: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            Model(track, Injekt.get<TrackerManager>().get(serviceId)!!, start, trackWriterFor(isNovel))
        }
        AlertDialogContent(
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
            icon = { Icon(imageVector = Icons.Default.Delete, contentDescription = null) },
            title = {
                Text(
                    text = stringResource(MR.strings.track_remove_date_conf_title),
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                val serviceName = screenModel.getServiceName()
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
                            screenModel.removeDate()
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

    private class Model(
        private val track: Track,
        private val tracker: Tracker,
        private val start: Boolean,
        private val writer: TrackWriter,
    ) : ScreenModel {

        fun getServiceName() = tracker.name

        fun removeDate() {
            screenModelScope.launchNonCancellable {
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
        val screenModel = rememberScreenModel {
            Model(entryId, currentUrl, initialQuery, Injekt.get<TrackerManager>().get(serviceId)!!, isNovel)
        }

        val state by screenModel.state.collectAsState()

        val textFieldState = rememberTextFieldState(initialQuery)
        TrackerSearch(
            state = textFieldState,
            onDispatchQuery = { screenModel.trackingSearch(textFieldState.text.toString()) },
            queryResult = state.queryResult,
            selected = state.selected,
            onSelectedChange = screenModel::updateSelection,
            onConfirmSelection = f@{ private: Boolean ->
                val selected = state.selected ?: return@f
                selected.private = private
                screenModel.registerTracking(selected)
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
            supportsPrivateTracking = screenModel.supportsPrivateTracking,
        )
    }

    private class Model(
        private val entryId: Long,
        private val currentUrl: String? = null,
        initialQuery: String,
        private val tracker: Tracker,
        private val isNovel: Boolean,
    ) : StateScreenModel<Model.State>(State()) {

        val supportsPrivateTracking = tracker.supportsPrivateTracking

        init {
            // Run search on first launch
            if (initialQuery.isNotBlank()) {
                trackingSearch(initialQuery)
            }
        }

        fun trackingSearch(query: String) {
            screenModelScope.launch {
                // To show loading state
                mutableState.update { it.copy(queryResult = null, selected = null) }

                val result = withIOContext {
                    try {
                        // Novels have their own catalogue on some trackers (e.g. a separate endpoint).
                        val results = if (isNovel) tracker.searchNovel(query) else tracker.search(query)
                        Result.success(results)
                    } catch (e: Throwable) {
                        Result.failure(e)
                    }
                }
                mutableState.update { oldState ->
                    oldState.copy(
                        queryResult = result,
                        selected = result.getOrNull()?.find { it.tracking_url == currentUrl },
                    )
                }
            }
        }

        fun registerTracking(item: TrackSearch) {
            screenModelScope.launchNonCancellable {
                if (isNovel) {
                    Injekt.get<AddNovelTrack>().bind(tracker, item, entryId)
                } else {
                    tracker.register(item, entryId)
                    // RK: share the new tracker with the rest of the merged group
                    Injekt.get<PropagateTrackerLinks>().fromSeed(entryId)
                }
            }
        }

        fun updateSelection(selected: TrackSearch) = mutableState.update { it.copy(selected = selected) }

        @Immutable
        data class State(
            val queryResult: Result<List<TrackSearch>>? = null,
            val selected: TrackSearch? = null,
        )
    }
}

private data class EntryTrackerRemoveScreen(
    private val entryId: Long,
    private val track: Track,
    private val serviceId: Long,
    private val isNovel: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            Model(entryId, track, Injekt.get<TrackerManager>().get(serviceId)!!, isNovel)
        }
        val serviceName = screenModel.getName()
        var removeRemoteTrack by remember { mutableStateOf(false) }
        AlertDialogContent(
            modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
            icon = { Icon(imageVector = Icons.Default.Delete, contentDescription = null) },
            title = {
                Text(
                    text = stringResource(MR.strings.track_delete_title, serviceName),
                    textAlign = TextAlign.Center,
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small)) {
                    Text(text = stringResource(MR.strings.track_delete_text, serviceName))
                    if (screenModel.isDeletable()) {
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
                            screenModel.unregisterTracking(serviceId)
                            if (removeRemoteTrack) screenModel.deleteEntryFromService()
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

    private class Model(
        private val entryId: Long,
        private val track: Track,
        private val tracker: Tracker,
        private val isNovel: Boolean,
    ) : ScreenModel {

        fun getName() = tracker.name

        fun isDeletable() = tracker is DeletableTracker

        fun deleteEntryFromService() {
            screenModelScope.launchNonCancellable {
                try {
                    (tracker as DeletableTracker).delete(track)
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to delete entry from service" }
                }
            }
        }

        fun unregisterTracking(serviceId: Long) {
            screenModelScope.launchNonCancellable {
                if (isNovel) {
                    // Group-aware: clear the tracker from every merged source so it doesn't reappear.
                    Injekt.get<DeleteNovelTrack>().awaitGroup(entryId, serviceId)
                } else {
                    Injekt.get<DeleteTrack>().await(entryId, serviceId)
                }
            }
        }
    }
}
