package reikai.presentation.novel.track

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
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.presentation.track.TrackChapterSelector
import eu.kanade.presentation.track.TrackDateSelector
import eu.kanade.presentation.track.TrackInfoDialogHome
import eu.kanade.presentation.track.TrackScoreSelector
import eu.kanade.presentation.track.TrackStatusSelector
import eu.kanade.presentation.track.TrackerSearch
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.data.track.DeletableTracker
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import eu.kanade.tachiyomi.util.lang.convertEpochMillisZone
import eu.kanade.tachiyomi.util.lang.toLocalDate
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import logcat.LogPriority
import reikai.domain.novel.interactor.AddNovelTrack
import reikai.domain.novel.interactor.DeleteNovelTrack
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.RefreshNovelTracks
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.track.NovelTrackUpdater
import reikai.domain.novel.track.toDbTrack
import reikai.domain.novel.track.toUiTrack
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.core.common.util.system.logcat
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
 * Novel twin of [eu.kanade.tachiyomi.ui.manga.track.TrackInfoDialogHomeScreen]. Reuses the manga
 * tracking composables verbatim (they are media-agnostic): novel tracks are adapted to the shared
 * [TrackItem] / domain Track carrier via [reikai.domain.novel.track.toUiTrack], and writes go through
 * [reikai.domain.novel.track.NovelTrackUpdater] / [AddNovelTrack] into `novel_tracks`.
 */
data class NovelTrackInfoDialogHomeScreen(
    private val novelId: Long,
    private val novelTitle: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { Model(novelId) }

        val dateFormat = remember { UiPreferences.dateFormat(Injekt.get<UiPreferences>().dateFormat.get()) }
        val state by screenModel.state.collectAsState()

        TrackInfoDialogHome(
            trackItems = state.trackItems,
            dateFormat = dateFormat,
            onStatusClick = {
                screenModel.novelTrackOf(it)?.let { track ->
                    navigator.push(NovelTrackStatusSelectorScreen(track, it.tracker.id))
                }
            },
            onChapterClick = {
                screenModel.novelTrackOf(it)?.let { track ->
                    navigator.push(NovelTrackChapterSelectorScreen(track, it.tracker.id))
                }
            },
            onScoreClick = {
                screenModel.novelTrackOf(it)?.let { track ->
                    navigator.push(NovelTrackScoreSelectorScreen(track, it.tracker.id))
                }
            },
            onStartDateEdit = {
                screenModel.novelTrackOf(it)?.let { track ->
                    navigator.push(NovelTrackDateSelectorScreen(track, it.tracker.id, start = true))
                }
            },
            onEndDateEdit = {
                screenModel.novelTrackOf(it)?.let { track ->
                    navigator.push(NovelTrackDateSelectorScreen(track, it.tracker.id, start = false))
                }
            },
            onNewSearch = {
                navigator.push(
                    NovelTrackerSearchScreen(
                        novelId = novelId,
                        initialQuery = it.track?.title ?: novelTitle,
                        currentUrl = it.track?.remoteUrl,
                        serviceId = it.tracker.id,
                    ),
                )
            },
            onOpenInBrowser = { openTrackerInBrowser(context, it) },
            onRemoved = {
                screenModel.novelTrackOf(it)?.let { track ->
                    navigator.push(NovelTrackerRemoveScreen(novelId, track, it.tracker.id))
                }
            },
            onCopyLink = { context.copyTrackerLink(it) },
            onTogglePrivate = { screenModel.togglePrivate(it) },
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
        private val novelId: Long,
        private val getNovelTracks: GetNovelTracks = Injekt.get(),
        private val updater: NovelTrackUpdater = Injekt.get(),
    ) : StateScreenModel<Model.State>(State()) {

        init {
            screenModelScope.launch { refreshTrackers() }

            screenModelScope.launch {
                // subscribeGroup spans the merge group, so a track bound on a sibling source shows here.
                getNovelTracks.subscribeGroup(novelId)
                    .catch { logcat(LogPriority.ERROR, it) }
                    .distinctUntilChanged()
                    .collectLatest { tracks ->
                        mutableState.update { it.copy(novelTracks = tracks, trackItems = tracks.mapToTrackItem()) }
                    }
            }
        }

        fun novelTrackOf(item: TrackItem): NovelTrack? =
            state.value.novelTracks.find { it.trackerId == item.tracker.id }

        fun togglePrivate(item: TrackItem) {
            val novelTrack = novelTrackOf(item) ?: return
            screenModelScope.launchNonCancellable {
                updater.setRemotePrivate(item.tracker, novelTrack.toDbTrack(), !novelTrack.private)
            }
        }

        private suspend fun refreshTrackers() {
            val context = Injekt.get<Application>()
            Injekt.get<RefreshNovelTracks>().await(novelId)
                .filter { it.first != null }
                .forEach { (track, e) ->
                    logcat(LogPriority.ERROR, e) {
                        "Failed to refresh novel track data novelId=$novelId for service ${track!!.id}"
                    }
                    withUIContext {
                        context.toast(context.stringResource(MR.strings.track_error, track!!.name, e.message ?: ""))
                    }
                }
        }

        private fun List<NovelTrack>.mapToTrackItem(): List<TrackItem> {
            // Only offer trackers with a real novel search; the rest would silently bind a manga hit.
            return Injekt.get<TrackerManager>().loggedInTrackers()
                .filter { it.supportsNovels }
                .map { service -> TrackItem(find { it.trackerId == service.id }?.toUiTrack(), service) }
        }

        @Immutable
        data class State(
            val novelTracks: List<NovelTrack> = emptyList(),
            val trackItems: List<TrackItem> = emptyList(),
        )
    }
}

private data class NovelTrackStatusSelectorScreen(
    private val track: NovelTrack,
    private val serviceId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            Model(track, Injekt.get<TrackerManager>().get(serviceId)!!)
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
        private val track: NovelTrack,
        private val tracker: Tracker,
        private val updater: NovelTrackUpdater = Injekt.get(),
    ) : StateScreenModel<Model.State>(State(track.status)) {

        fun getSelections(): Map<Long, StringResource?> =
            tracker.getStatusList().associateWith { tracker.getStatus(it) }

        fun setSelection(selection: Long) = mutableState.update { it.copy(selection = selection) }

        fun setStatus() {
            screenModelScope.launchNonCancellable {
                updater.setRemoteStatus(tracker, track.toDbTrack(), state.value.selection)
            }
        }

        @Immutable
        data class State(val selection: Long)
    }
}

private data class NovelTrackChapterSelectorScreen(
    private val track: NovelTrack,
    private val serviceId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            Model(track, Injekt.get<TrackerManager>().get(serviceId)!!)
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
        private val track: NovelTrack,
        private val tracker: Tracker,
        private val updater: NovelTrackUpdater = Injekt.get(),
    ) : StateScreenModel<Model.State>(State(track.lastChapterRead.toInt())) {

        fun getRange(): Iterable<Int> {
            val endRange = if (track.totalChapters > 0) track.totalChapters else 10000
            return 0..endRange.toInt()
        }

        fun setSelection(selection: Int) = mutableState.update { it.copy(selection = selection) }

        fun setChapter() {
            screenModelScope.launchNonCancellable {
                updater.setRemoteLastChapterRead(tracker, track.toDbTrack(), state.value.selection)
            }
        }

        @Immutable
        data class State(val selection: Int)
    }
}

private data class NovelTrackScoreSelectorScreen(
    private val track: NovelTrack,
    private val serviceId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            Model(track, Injekt.get<TrackerManager>().get(serviceId)!!)
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
        private val track: NovelTrack,
        private val tracker: Tracker,
        private val updater: NovelTrackUpdater = Injekt.get(),
    ) : StateScreenModel<Model.State>(State(tracker.displayScore(track.toUiTrack()))) {

        fun getSelections(): List<String> = tracker.getScoreList()

        fun setSelection(selection: String) = mutableState.update { it.copy(selection = selection) }

        fun setScore() {
            screenModelScope.launchNonCancellable {
                updater.setRemoteScore(tracker, track.toDbTrack(), state.value.selection)
            }
        }

        @Immutable
        data class State(val selection: String)
    }
}

private data class NovelTrackDateSelectorScreen(
    private val track: NovelTrack,
    private val serviceId: Long,
    private val start: Boolean,
) : Screen() {

    @Transient
    private val selectableDates = object : SelectableDates {
        override fun isSelectableDate(utcTimeMillis: Long): Boolean {
            val targetDate = Instant.ofEpochMilli(utcTimeMillis).toLocalDate(ZoneOffset.UTC)
            if (targetDate > LocalDate.now(ZoneOffset.UTC)) return false
            return when {
                start && track.finishDate > 0 -> {
                    val finishDate = Instant.ofEpochMilli(track.finishDate).toLocalDate(ZoneOffset.UTC)
                    targetDate <= finishDate
                }
                !start && track.startDate > 0 -> {
                    val startDate = Instant.ofEpochMilli(track.startDate).toLocalDate(ZoneOffset.UTC)
                    startDate <= targetDate
                }
                else -> true
            }
        }

        override fun isSelectableYear(year: Int): Boolean {
            if (year > LocalDate.now(ZoneOffset.UTC).year) return false
            return when {
                start && track.finishDate > 0 -> {
                    val finishDate = Instant.ofEpochMilli(track.finishDate).toLocalDate(ZoneOffset.UTC)
                    year <= finishDate.year
                }
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
            Model(track, Injekt.get<TrackerManager>().get(serviceId)!!, start)
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
        private val track: NovelTrack,
        private val tracker: Tracker,
        private val start: Boolean,
        private val updater: NovelTrackUpdater = Injekt.get(),
    ) : ScreenModel {

        val initialSelection: Long
            get() {
                val millis = (if (start) track.startDate else track.finishDate)
                    .takeIf { it != 0L }
                    ?: Instant.now().toEpochMilli()
                return millis.convertEpochMillisZone(ZoneOffset.systemDefault(), ZoneOffset.UTC)
            }

        fun setDate(millis: Long) {
            val localMillis = millis.convertEpochMillisZone(ZoneOffset.UTC, ZoneOffset.systemDefault())
            screenModelScope.launchNonCancellable {
                if (start) {
                    updater.setRemoteStartDate(tracker, track.toDbTrack(), localMillis)
                } else {
                    updater.setRemoteFinishDate(tracker, track.toDbTrack(), localMillis)
                }
            }
        }

        fun confirmRemoveDate(navigator: Navigator) {
            navigator.push(NovelTrackDateRemoverScreen(track, tracker.id, start))
        }
    }
}

private data class NovelTrackDateRemoverScreen(
    private val track: NovelTrack,
    private val serviceId: Long,
    private val start: Boolean,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            Model(track, Injekt.get<TrackerManager>().get(serviceId)!!, start)
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
                            navigator.popUntil { it is NovelTrackInfoDialogHomeScreen }
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
        private val track: NovelTrack,
        private val tracker: Tracker,
        private val start: Boolean,
        private val updater: NovelTrackUpdater = Injekt.get(),
    ) : ScreenModel {

        fun getServiceName() = tracker.name

        fun removeDate() {
            screenModelScope.launchNonCancellable {
                if (start) {
                    updater.setRemoteStartDate(tracker, track.toDbTrack(), 0)
                } else {
                    updater.setRemoteFinishDate(tracker, track.toDbTrack(), 0)
                }
            }
        }
    }
}

data class NovelTrackerSearchScreen(
    private val novelId: Long,
    private val initialQuery: String,
    private val currentUrl: String?,
    private val serviceId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            Model(novelId, currentUrl, initialQuery, Injekt.get<TrackerManager>().get(serviceId)!!)
        }

        val state by screenModel.state.collectAsState()

        val textFieldState = rememberTextFieldState(initialQuery)
        TrackerSearch(
            state = textFieldState,
            onDispatchQuery = { screenModel.trackingSearch(textFieldState.text.toString()) },
            queryResult = state.queryResult,
            selected = state.selected,
            onSelectedChange = screenModel::updateSelection,
            onConfirmSelection = f@{ _: Boolean ->
                val selected = state.selected ?: return@f
                screenModel.registerTracking(selected)
                navigator.pop()
            },
            onDismissRequest = navigator::pop,
            // Private listing is set after binding, via the Private toggle on the track sheet.
            supportsPrivateTracking = false,
        )
    }

    private class Model(
        private val novelId: Long,
        private val currentUrl: String? = null,
        initialQuery: String,
        private val tracker: Tracker,
    ) : StateScreenModel<Model.State>(State()) {

        init {
            if (initialQuery.isNotBlank()) trackingSearch(initialQuery)
        }

        fun trackingSearch(query: String) {
            screenModelScope.launch {
                mutableState.update { it.copy(queryResult = null, selected = null) }

                val result = withIOContext {
                    try {
                        Result.success(tracker.searchNovel(query))
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
                Injekt.get<AddNovelTrack>().bind(tracker, item, novelId)
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

private data class NovelTrackerRemoveScreen(
    private val novelId: Long,
    private val track: NovelTrack,
    private val serviceId: Long,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel {
            Model(novelId, track, Injekt.get<TrackerManager>().get(serviceId)!!)
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
                            if (removeRemoteTrack) screenModel.deleteNovelFromService()
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
        private val novelId: Long,
        private val track: NovelTrack,
        private val tracker: Tracker,
        private val deleteNovelTrack: DeleteNovelTrack = Injekt.get(),
    ) : ScreenModel {

        fun getName() = tracker.name

        fun isDeletable() = tracker is DeletableTracker

        fun deleteNovelFromService() {
            screenModelScope.launchNonCancellable {
                try {
                    (tracker as DeletableTracker).delete(track.toUiTrack())
                } catch (e: Exception) {
                    logcat(LogPriority.ERROR, e) { "Failed to delete novel entry from service" }
                }
            }
        }

        fun unregisterTracking(serviceId: Long) {
            // Group-aware: clear the tracker from every merged source so it doesn't reappear from a sibling.
            screenModelScope.launchNonCancellable { deleteNovelTrack.awaitGroup(novelId, serviceId) }
        }
    }
}
