package reikai.presentation.details

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.track.components.TrackLogoIcon
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.Close
import reikai.presentation.components.entryStatusRes
import reikai.presentation.track.trackerErrorMessage
import reikai.util.runCatchingCancellable
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Backs the "Fill from tracker" button. [candidates] resolves the entry's bound trackers (manga and
 * novel each supply their own enumerator from the details ViewModel), [fetch] pulls one tracker's
 * metadata. An entry with no bound tracker keeps the button, which says so when tapped.
 */
class TrackerAutofill(
    val candidates: suspend () -> List<Pair<Track, Tracker>>,
    val fetch: suspend (Track, Tracker) -> TrackMangaMetadata,
)

/**
 * Neutral edit-info values shared by both content types. The caller seeds [EntryEditInfoDialog] with the
 * current effective values and maps the edited result back to its own custom-info overlay, which leaves
 * the source row untouched.
 */
data class EntryEditInfoUi(
    val title: String,
    val author: String,
    val artist: String,
    val description: String,
    val genre: List<String>,
    val status: Long,
    val thumbnailUrl: String,
)

/**
 * The shared manga/novel edit-info editor, on the sheet SY's editor uses. It takes the details page's
 * theme, so it follows the cover only while Theme based on cover is on. The cover preview loads once
 * through [coverModel], the app's registered fetcher, so header-protected hosts work; a new URL applies
 * on Save. [mergedTags] gives a merged series' source tags and is null for one that is not merged, which
 * hides Collate tags.
 */
@Composable
fun EntryEditInfoDialog(
    initial: EntryEditInfoUi,
    // The raw source values (no overlay), which Reset info and Reset tags restore in the form.
    source: EntryEditInfoUi,
    coverModel: (thumbnailUrl: String) -> Any?,
    onDismissRequest: () -> Unit,
    onSave: (EntryEditInfoUi) -> Unit,
    // "Reset all": clear every override and close (each caller clears its overlay).
    onResetAll: () -> Unit,
    autofill: TrackerAutofill,
    mergedTags: suspend () -> List<String>?,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val form = remember { EditInfoForm(initial) }
    val scroll = rememberScrollState()
    var isDraggingTag by remember { mutableStateOf(false) }
    var pickerTracks by remember { mutableStateOf<List<Pair<Track, Tracker>>?>(null) }
    var isAddingTags by remember { mutableStateOf(false) }
    var collatable by remember { mutableStateOf<List<String>?>(null) }
    LaunchedEffect(Unit) { collatable = runCatchingCancellable { mergedTags() }.getOrNull() }

    val fillFromTracker: (Track, Tracker) -> Unit = { track, tracker ->
        scope.launch {
            runTrackerFill(
                tracker = tracker,
                fetch = { autofill.fetch(track, tracker) },
                onFilled = form::fill,
                onFailed = { e ->
                    logcat(LogPriority.ERROR, e) { "Fill from tracker failed (${tracker.name})" }
                    val message = if (isMissingOnTracker(e)) {
                        context.stringResource(MR.strings.track_autofill_not_found, tracker.name)
                    } else {
                        context.trackerErrorMessage(tracker.name, e)
                    }
                    context.toast(message)
                },
            )
        }
    }

    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column {
            var viewport by remember { mutableStateOf(Rect.Zero) }
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .onGloballyPositioned { viewport = it.boundsInRoot() }
                    // Off while a tag is held: the form's own scroll otherwise tracks the same finger and
                    // can take over mid-drag with the whole distance at once.
                    .verticalScroll(scroll, enabled = !isDraggingTag)
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AsyncImage(
                    model = remember { coverModel(initial.thumbnailUrl) },
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .padding(vertical = 8.dp)
                        .size(width = 100.dp, height = 150.dp)
                        .clip(RoundedCornerShape(4.dp)),
                )
                StatusField(selected = form.status, onSelect = { form.status = it })
                InfoField(form.title, stringResource(MR.strings.title))
                InfoField(form.author, stringResource(MR.strings.author))
                InfoField(form.artist, stringResource(MR.strings.artist))
                InfoField(
                    form.thumbnailUrl,
                    stringResource(MR.strings.edit_info_cover_url),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                InfoField(form.description, stringResource(MR.strings.edit_info_description), minLines = 3)
                TagChips(
                    tags = form.tags,
                    scroll = scroll,
                    viewport = { viewport },
                    onDraggingChange = { isDraggingTag = it },
                    onAddClick = { isAddingTags = true },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                val candidates = autofill.candidates()
                                when {
                                    candidates.isEmpty() -> context.toast(
                                        context.stringResource(MR.strings.entry_not_tracked),
                                    )
                                    candidates.size == 1 -> fillFromTracker(candidates[0].first, candidates[0].second)
                                    else -> pickerTracks = candidates
                                }
                            }
                        },
                    ) { Text(stringResource(MR.strings.action_fill_from_tracker)) }
                    collatable?.let { tags ->
                        FilledTonalButton(onClick = { form.append(tags) }) {
                            Text(stringResource(MR.strings.action_collate_tags))
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Reset info keeps the chips, so Fill from tracker can seed tags and Reset info drop the rest.
                    FilledTonalButton(onClick = { form.setInfo(source) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(MR.strings.action_reset_info))
                    }
                    FilledTonalButton(onClick = { form.setTags(source.genre) }, modifier = Modifier.weight(1f)) {
                        Text(stringResource(MR.strings.action_reset_tags))
                    }
                    FilledTonalButton(onClick = onResetAll, modifier = Modifier.weight(1f)) {
                        Text(stringResource(MR.strings.action_reset_all))
                    }
                }
            }
            HorizontalDivider()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
                TextButton(
                    onClick = {
                        onSave(form.collect())
                        onDismissRequest()
                    },
                ) { Text(stringResource(MR.strings.action_save)) }
            }
        }
    }

    if (isAddingTags) {
        AddTagsDialog(
            onDismissRequest = { isAddingTags = false },
            onConfirm = { form.append(typedTags(it)) },
        )
    }

    pickerTracks?.let { tracks ->
        TrackerSelectDialog(
            tracks = tracks,
            onDismissRequest = { pickerTracks = null },
            onTrackerSelect = { tracker, track ->
                pickerTracks = null
                fillFromTracker(track, tracker)
            },
        )
    }
}

/** The form's editable values. The status index is the stored status code, with Default in the unknown slot. */
private class EditInfoForm(initial: EntryEditInfoUi) {
    val title = TextFieldState()
    val author = TextFieldState()
    val artist = TextFieldState()
    val thumbnailUrl = TextFieldState()
    val description = TextFieldState()
    var status by mutableIntStateOf(0)
    val tags: SnapshotStateList<String> = initial.genre.filter { it.isNotBlank() }.toMutableStateList()

    init {
        setInfo(initial)
    }

    fun setInfo(ui: EntryEditInfoUi) {
        status = ui.status.toInt().coerceIn(0, STATUS_CODES.last)
        title.setTextAndPlaceCursorAtEnd(ui.title)
        author.setTextAndPlaceCursorAtEnd(ui.author)
        artist.setTextAndPlaceCursorAtEnd(ui.artist)
        thumbnailUrl.setTextAndPlaceCursorAtEnd(ui.thumbnailUrl)
        description.setTextAndPlaceCursorAtEnd(ui.description)
    }

    fun setTags(new: List<String>) {
        tags.clear()
        tags.addAll(new.filter { it.isNotBlank() })
    }

    fun append(added: List<String>) = setTags(appendTags(tags, added))

    /** Text fields replace only where the tracker has a value; its genres are appended to the chips. */
    fun fill(meta: TrackMangaMetadata) {
        listOf(
            title to meta.title,
            author to meta.authors,
            artist to meta.artists,
            thumbnailUrl to meta.thumbnailUrl,
            description to meta.description,
        ).forEach { (field, value) -> if (!value.isNullOrBlank()) field.setTextAndPlaceCursorAtEnd(value) }
        append(meta.genres.orEmpty())
    }

    fun collect() = EntryEditInfoUi(
        title = title.text.toString(),
        author = author.text.toString(),
        artist = artist.text.toString(),
        description = description.text.toString(),
        genre = tags.toList(),
        status = status.toLong(),
        thumbnailUrl = thumbnailUrl.text.toString(),
    )
}

private val STATUS_CODES = SManga.UNKNOWN..SManga.ON_HIATUS

private fun statusLabel(code: Int) = when (code) {
    SManga.UNKNOWN -> MR.strings.label_default
    else -> entryStatusRes(code.toLong())
}

/** A plain centred dropdown, as SY's editor shows the status, rather than an outlined field. */
@Composable
private fun StatusField(selected: Int, onSelect: (Int) -> Unit) {
    val labels = STATUS_CODES.map(::statusLabel)
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally),
    ) {
        Row(
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .widthIn(min = 220.dp)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(labels[selected]), style = MaterialTheme.typography.titleMedium)
            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
        }
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            labels.forEachIndexed { index, label ->
                DropdownMenuItem(
                    text = { Text(stringResource(label)) },
                    onClick = {
                        expanded = false
                        onSelect(index)
                    },
                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding,
                )
            }
        }
    }
}

@Composable
private fun InfoField(
    state: TextFieldState,
    label: String,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
) {
    OutlinedTextField(
        state = state,
        label = { Text(label) },
        lineLimits = if (minLines > 1) {
            TextFieldLineLimits.MultiLine(minHeightInLines = minLines)
        } else {
            TextFieldLineLimits.SingleLine
        },
        keyboardOptions = keyboardOptions,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The tag chips, each removable and draggable into a new place (after SY's ReorderableTagFlow). A drag
 * clears focus, since a focused field pulls the form back to itself; holding a chip near the form's top
 * or bottom edge scrolls it; and a drop goes where [tagDropIndex] says.
 */
@Composable
private fun TagChips(
    tags: SnapshotStateList<String>,
    scroll: ScrollState,
    viewport: () -> Rect,
    onDraggingChange: (Boolean) -> Unit,
    onAddClick: () -> Unit,
) {
    val focusManager = LocalFocusManager.current
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    val chipBounds = remember { mutableStateMapOf<Int, Rect>() }
    var dragIndex by remember { mutableStateOf<Int?>(null) }
    var dragTranslation by remember { mutableStateOf(Offset.Zero) }

    fun endDrag() {
        dragIndex = null
        dragTranslation = Offset.Zero
        onDraggingChange(false)
    }

    // While a chip is held near an edge, scroll and carry the chip along so it stays under the finger.
    LaunchedEffect(dragIndex) {
        val index = dragIndex ?: return@LaunchedEffect
        while (true) {
            withFrameNanos { }
            val pointerY = (chipBounds[index] ?: continue).center.y + dragTranslation.y
            val view = viewport()
            val depth = when {
                pointerY > view.bottom - edge -> (pointerY - (view.bottom - edge)) / edge
                pointerY < view.top + edge -> (pointerY - (view.top + edge)) / edge
                else -> 0f
            }
            if (depth != 0f) {
                val moved = scroll.scrollBy(EDGE_SCROLL_PX * depth.coerceIn(-1.5f, 1.5f))
                dragTranslation += Offset(0f, moved)
            }
        }
    }

    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tags.forEachIndexed { index, tag ->
            val isDragging = dragIndex == index
            InputChip(
                selected = false,
                onClick = {},
                label = { Text(tag) },
                trailingIcon = {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Close,
                        contentDescription = stringResource(MR.strings.action_remove),
                        modifier = Modifier
                            .size(InputChipDefaults.IconSize)
                            .clickable { tags.removeAt(index) },
                    )
                },
                colors = InputChipDefaults.inputChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    trailingIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
                ),
                modifier = Modifier
                    .onGloballyPositioned { chipBounds[index] = it.boundsInRoot() }
                    .zIndex(if (isDragging) 1f else 0f)
                    .graphicsLayer {
                        if (isDragging) {
                            translationX = dragTranslation.x
                            translationY = dragTranslation.y
                            scaleX = 1.05f
                            scaleY = 1.05f
                        }
                    }
                    .pointerInput(index) {
                        detectDragGestures(
                            onDragStart = {
                                focusManager.clearFocus()
                                dragIndex = index
                                dragTranslation = Offset.Zero
                                onDraggingChange(true)
                            },
                            onDragEnd = {
                                val drop = (chipBounds[index] ?: Rect.Zero).center + dragTranslation
                                // Bounds of a removed chip linger in the map, so read only the live indices.
                                val target = tagDropIndex(tags.indices.map { chipBounds[it] ?: Rect.Zero }, drop)
                                if (target != null && target != index) tags.add(target, tags.removeAt(index))
                                endDrag()
                            },
                            onDragCancel = ::endDrag,
                            onDrag = { change, amount ->
                                change.consume()
                                dragTranslation += amount
                            },
                        )
                    },
            )
        }
        AssistChip(
            onClick = onAddClick,
            label = { Text(stringResource(MR.strings.edit_info_add_tags)) },
            leadingIcon = {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(AssistChipDefaults.IconSize),
                )
            },
        )
    }
}

private const val EDGE_SCROLL_PX = 12f

/** Several tags typed at once, as SY's editor takes them. Done on the keyboard adds them too. */
@Composable
private fun AddTagsDialog(onDismissRequest: () -> Unit, onConfirm: (String) -> Unit) {
    val input = remember { TextFieldState() }
    val confirm = {
        onConfirm(input.text.toString())
        onDismissRequest()
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { TextButton(onClick = confirm) { Text(stringResource(MR.strings.action_ok)) } },
        dismissButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
        title = { Text(stringResource(MR.strings.edit_info_add_tags)) },
        text = {
            OutlinedTextField(
                state = input,
                supportingText = { Text(stringResource(MR.strings.edit_info_add_tags_hint)) },
                lineLimits = TextFieldLineLimits.SingleLine,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                onKeyboardAction = { confirm() },
                modifier = Modifier.fillMaxWidth(),
            )
        },
    )
}

/** Tracker chooser shown when more than one tracker is bound (ported from Komikku's EditMangaDialog). */
@Composable
private fun TrackerSelectDialog(
    tracks: List<Pair<Track, Tracker>>,
    onDismissRequest: () -> Unit,
    onTrackerSelect: (tracker: Tracker, track: Track) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
        },
        title = { Text(stringResource(MR.strings.select_tracker)) },
        text = {
            FlowRow(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                tracks.forEach { (track, tracker) ->
                    TrackLogoIcon(tracker, onClick = { onTrackerSelect(tracker, track) })
                }
            }
        },
    )
}
