package yokai.presentation.details.track

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.database.models.Track
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.ui.manga.track.TrackItem
import java.text.DateFormat
import java.util.Date
import yokai.presentation.component.TrackLogoIcon
import yokai.presentation.component.WheelNumberPicker
import yokai.presentation.component.WheelTextPicker

/**
 * Tracking modal for the Compose details screen, styled after Mihon's tracking dialog. The home card
 * lists each logged-in tracker (logo, title, overflow) with a bordered status / chapters / score
 * (and dates) panel; tapping a cell opens a confirm-style selector dialog layered on top. Pure UI:
 * the caller's [TrackInfoScreenModel] owns all state and side-effects. Hardcoded English strings
 * until the Phase 9 localization pass.
 */
// no ScreenModel: pure UI, state owned by the caller's TrackInfoScreenModel.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrackInfoDialog(
    state: TrackInfoState,
    onSearchQueryChange: (String) -> Unit,
    onSearch: (serviceId: Long, query: String) -> Unit,
    onOpenSearch: (serviceId: Long) -> Unit,
    onRegister: (serviceId: Long, item: TrackSearch, private: Boolean) -> Unit,
    onSetPrivate: (serviceId: Long, private: Boolean) -> Unit,
    onOpenStatus: (serviceId: Long) -> Unit,
    onOpenScore: (serviceId: Long) -> Unit,
    onOpenChapters: (serviceId: Long) -> Unit,
    onOpenDate: (serviceId: Long, isStart: Boolean) -> Unit,
    onOpenRemove: (serviceId: Long) -> Unit,
    onSetStatus: (serviceId: Long, index: Int) -> Unit,
    onSetScore: (serviceId: Long, index: Int) -> Unit,
    onSetChapters: (serviceId: Long, chapterNumber: Float) -> Unit,
    onSetDate: (serviceId: Long, isStart: Boolean, date: Long) -> Unit,
    onRemove: (serviceId: Long, alsoRemoveFromService: Boolean) -> Unit,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            HomeContent(
                state = state,
                onOpenSearch = onOpenSearch,
                onSetPrivate = onSetPrivate,
                onOpenStatus = onOpenStatus,
                onOpenScore = onOpenScore,
                onOpenChapters = onOpenChapters,
                onOpenDate = onOpenDate,
                onOpenRemove = onOpenRemove,
            )
        }
    }

    when (val page = state.page) {
        TrackPage.Home -> Unit

        is TrackPage.Search -> {
            val service = state.items.find { it.service.id == page.serviceId }?.service
            if (service != null) {
                SearchDialog(
                    service = service,
                    state = state,
                    onQueryChange = onSearchQueryChange,
                    onSearch = { onSearch(page.serviceId, state.searchQuery) },
                    onPick = { item, private -> onRegister(page.serviceId, item, private) },
                    onDismiss = onBack,
                )
            }
        }

        is TrackPage.SetStatus -> WithTrack(state, page.serviceId) { service, track ->
            StatusDialog(service, track, onConfirm = { onSetStatus(page.serviceId, it) }, onDismiss = onBack)
        }

        is TrackPage.SetScore -> WithTrack(state, page.serviceId) { service, track ->
            ScoreDialog(service, track, onConfirm = { onSetScore(page.serviceId, it) }, onDismiss = onBack)
        }

        is TrackPage.SetChapters -> WithTrack(state, page.serviceId) { _, track ->
            ChaptersDialog(track, onConfirm = { onSetChapters(page.serviceId, it) }, onDismiss = onBack)
        }

        is TrackPage.SetDate -> WithTrack(state, page.serviceId) { _, track ->
            DateDialog(
                isStart = page.isStart,
                currentDate = if (page.isStart) track.started_reading_date else track.finished_reading_date,
                suggestedDate = state.suggestedDate,
                onConfirm = { onSetDate(page.serviceId, page.isStart, it) },
                onClear = { onSetDate(page.serviceId, page.isStart, 0L) },
                onDismiss = onBack,
            )
        }

        is TrackPage.Remove -> WithTrack(state, page.serviceId) { service, _ ->
            RemoveDialog(service, onConfirm = { onRemove(page.serviceId, it) }, onDismiss = onBack)
        }
    }
}

@Composable
private fun WithTrack(
    state: TrackInfoState,
    serviceId: Long,
    content: @Composable (service: TrackService, track: Track) -> Unit,
) {
    val item = state.items.find { it.service.id == serviceId }
    val track = item?.track
    if (item != null && track != null) content(item.service, track)
}

@Composable
private fun HomeContent(
    state: TrackInfoState,
    onOpenSearch: (Long) -> Unit,
    onSetPrivate: (Long, Boolean) -> Unit,
    onOpenStatus: (Long) -> Unit,
    onOpenScore: (Long) -> Unit,
    onOpenChapters: (Long) -> Unit,
    onOpenDate: (Long, Boolean) -> Unit,
    onOpenRemove: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 560.dp)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("Tracking", style = MaterialTheme.typography.titleLarge)
        when {
            state.loading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            state.items.isEmpty() -> Text(
                "No trackers are logged in. Add one in Settings > Tracking.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> state.items.forEach { item ->
                if (item.track != null) {
                    TrackInfoItem(
                        item = item,
                        onOpenSearch = onOpenSearch,
                        onSetPrivate = onSetPrivate,
                        onOpenStatus = onOpenStatus,
                        onOpenScore = onOpenScore,
                        onOpenChapters = onOpenChapters,
                        onOpenDate = onOpenDate,
                        onOpenRemove = onOpenRemove,
                    )
                } else {
                    TrackInfoItemEmpty(item, onOpenSearch)
                }
            }
        }
    }
}

@Composable
private fun TrackInfoItem(
    item: TrackItem,
    onOpenSearch: (Long) -> Unit,
    onSetPrivate: (Long, Boolean) -> Unit,
    onOpenStatus: (Long) -> Unit,
    onOpenScore: (Long) -> Unit,
    onOpenChapters: (Long) -> Unit,
    onOpenDate: (Long, Boolean) -> Unit,
    onOpenRemove: (Long) -> Unit,
) {
    val service = item.service
    val track = item.track ?: return
    val id = service.id
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val supportsScoring = service.getScoreList().isNotEmpty()

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TrackLogoIcon(tracker = service, onClick = { openUri(uriHandler, track.tracking_url) })
            Row(
                modifier = Modifier
                    .height(48.dp)
                    .weight(1f)
                    .clickable { onOpenSearch(id) }
                    .padding(start = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (track.private) {
                    Icon(
                        Icons.Filled.VisibilityOff,
                        contentDescription = "Private",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    text = track.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            VDivider()
            ItemMenu(
                isPrivate = track.private,
                onOpenInBrowser = { openUri(uriHandler, track.tracking_url) },
                onCopyLink = { if (track.tracking_url.isNotBlank()) clipboard.setText(AnnotatedString(track.tracking_url)) },
                onTogglePrivate = if (service.supportsPrivateTracking) ({ onSetPrivate(id, !track.private) }) else null,
                onRemove = { onOpenRemove(id) },
            )
        }

        Box(
            modifier = Modifier
                .padding(top = 12.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(8.dp)
                .clip(RoundedCornerShape(6.dp)),
        ) {
            Column {
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    DetailCell(Modifier.weight(1f), text = service.getStatus(track.status), onClick = { onOpenStatus(id) })
                    VDivider()
                    DetailCell(Modifier.weight(1f), text = chapterText(track.last_chapter_read, track.total_chapters), onClick = { onOpenChapters(id) })
                    if (supportsScoring) {
                        VDivider()
                        DetailCell(
                            Modifier.weight(1f),
                            text = service.displayScore(track).takeIf { track.score != 0f },
                            placeholder = "Score",
                            onClick = { onOpenScore(id) },
                        )
                    }
                }
                if (service.supportsReadingDates) {
                    HorizontalDivider()
                    Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                        DetailCell(
                            Modifier.weight(1f),
                            text = dateText(track.started_reading_date),
                            placeholder = "Start date",
                            onClick = { onOpenDate(id, true) },
                        )
                        VDivider()
                        DetailCell(
                            Modifier.weight(1f),
                            text = dateText(track.finished_reading_date),
                            placeholder = "Finish date",
                            onClick = { onOpenDate(id, false) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailCell(
    modifier: Modifier = Modifier,
    text: String?,
    placeholder: String = "",
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier.clickable(onClick = onClick).fillMaxHeight().padding(12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text ?: placeholder,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (text == null) 0.5f else 1f),
        )
    }
}

@Composable
private fun TrackInfoItemEmpty(item: TrackItem, onOpenSearch: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TrackLogoIcon(tracker = item.service)
        TextButton(
            onClick = { onOpenSearch(item.service.id) },
            modifier = Modifier.padding(start = 16.dp).weight(1f),
        ) {
            Text("Add tracking")
        }
    }
}

@Composable
private fun ItemMenu(
    isPrivate: Boolean,
    onOpenInBrowser: () -> Unit,
    onCopyLink: () -> Unit,
    onTogglePrivate: (() -> Unit)?,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Open in browser") }, onClick = { onOpenInBrowser(); expanded = false })
            DropdownMenuItem(text = { Text("Copy link") }, onClick = { onCopyLink(); expanded = false })
            if (onTogglePrivate != null) {
                DropdownMenuItem(
                    text = { Text(if (isPrivate) "Make public" else "Track privately") },
                    onClick = { onTogglePrivate(); expanded = false },
                )
            }
            DropdownMenuItem(text = { Text("Remove") }, onClick = { onRemove(); expanded = false })
        }
    }
}

@Composable
private fun VDivider() {
    Box(
        Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

@Composable
private fun StatusDialog(
    service: TrackService,
    track: Track,
    onConfirm: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val statuses = service.getStatusList()
    var selected by remember { mutableIntStateOf(statuses.indexOf(track.status).coerceAtLeast(0)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Status") },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                statuses.forEachIndexed { index, status ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected = index == selected, onClick = { selected = index })
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = index == selected, onClick = null)
                        Text(service.getStatus(status), modifier = Modifier.padding(start = 16.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ScoreDialog(
    service: TrackService,
    track: Track,
    onConfirm: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scores = remember(service) { service.getScoreList().toList() }
    val startIndex = scores.indices.firstOrNull { service.indexToScore(it) == track.score } ?: 0
    var selected by remember { mutableIntStateOf(startIndex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Score") },
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                WheelTextPicker(items = scores, startIndex = startIndex, onSelectionChanged = { selected = it })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ChaptersDialog(
    track: Track,
    onConfirm: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val max = if (track.total_chapters > 0L) track.total_chapters.toInt() else (track.last_chapter_read.toInt() + 100)
    val items = remember(max) { (0..max).toList() }
    val startIndex = track.last_chapter_read.toInt().coerceIn(0, max)
    var selected by remember { mutableIntStateOf(startIndex) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Chapters read") },
        text = {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                WheelNumberPicker(items = items, startIndex = startIndex, onSelectionChanged = { selected = it })
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(items[selected].toFloat()) }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateDialog(
    isStart: Boolean,
    currentDate: Long,
    suggestedDate: Long?,
    onConfirm: (Long) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = currentDate.takeIf { it > 0L } ?: suggestedDate ?: System.currentTimeMillis()
    val pickerState = rememberDatePickerState(initialSelectedDateMillis = initial)
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = if (isStart) "Start date" else "Finish date",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, top = 16.dp),
                )
                DatePicker(state = pickerState, title = null, headline = null, showModeToggle = false)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClear) { Text("Remove") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = { pickerState.selectedDateMillis?.let(onConfirm) }) { Text("OK") }
                }
            }
        }
    }
}

@Composable
private fun RemoveDialog(
    service: TrackService,
    onConfirm: (alsoRemoveFromService: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var alsoRemove by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop tracking?") },
        text = {
            Column {
                Text("Remove this tracker from the title?", style = MaterialTheme.typography.bodyMedium)
                if (service.canRemoveFromService()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { alsoRemove = !alsoRemove }.padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = alsoRemove, onCheckedChange = { alsoRemove = it })
                        Text("Also remove from ${stringResource(service.nameRes())}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(alsoRemove) }) { Text("Remove") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SearchDialog(
    service: TrackService,
    state: TrackInfoState,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onPick: (item: TrackSearch, private: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<TrackSearch?>(null) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(0.95f).fillMaxHeight(0.9f),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    Text(stringResource(service.nameRes()), style = MaterialTheme.typography.titleMedium)
                }
                OutlinedTextField(
                    value = state.searchQuery,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text("Search title") },
                    trailingIcon = {
                        IconButton(onClick = onSearch) { Icon(Icons.Filled.Search, contentDescription = "Search") }
                    },
                )
                Spacer(Modifier.size(8.dp))
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        state.searchLoading -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                        state.searchError != null -> Text(state.searchError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        state.searchResults.isEmpty() -> Text("No results.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.searchResults) { result ->
                                SearchResultCard(result = result, selected = result == selected, onClick = { selected = result })
                            }
                        }
                    }
                }
                Spacer(Modifier.size(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { selected?.let { onPick(it, false) } },
                        enabled = selected != null,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Track")
                    }
                    if (service.supportsPrivateTracking) {
                        Button(
                            onClick = { selected?.let { onPick(it, true) } },
                            enabled = selected != null,
                        ) {
                            Icon(Icons.Filled.VisibilityOff, contentDescription = "Track privately")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultCard(result: TrackSearch, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(width = 2.dp, color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent, shape = shape)
            .clickable(onClick = onClick)
            .padding(12.dp),
    ) {
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.align(Alignment.TopEnd),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column {
            Row {
                AsyncImage(
                    model = result.cover_url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.width(64.dp).height(96.dp).clip(RoundedCornerShape(8.dp)),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        result.title,
                        modifier = Modifier.padding(end = 28.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (result.publishing_type.isNotBlank()) DetailLine("Type", result.publishing_type)
                    if (result.start_date.isNotBlank()) DetailLine("Started", result.start_date)
                    if (result.publishing_status.isNotBlank()) DetailLine("Status", result.publishing_status)
                    if (result.score > 0f) DetailLine("Score", result.score.toString())
                }
            }
            if (result.summary.isNotBlank()) {
                Text(
                    result.summary.trim(),
                    modifier = Modifier.padding(top = 8.dp),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DetailLine(title: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, maxLines = 1, style = MaterialTheme.typography.titleSmall)
        Text(
            text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun openUri(uriHandler: androidx.compose.ui.platform.UriHandler, url: String) {
    if (url.isNotBlank()) runCatching { uriHandler.openUri(url) }
}

private fun chapterText(read: Float, total: Long): String {
    val readText = if (read % 1f == 0f) read.toInt().toString() else read.toString()
    return if (total > 0L) "$readText / $total" else readText
}

private fun dateText(date: Long): String? =
    if (date <= 0L) null else DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(date))
