package reikai.presentation.details

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import coil3.load
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.track.components.TrackLogoIcon
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackMangaMetadata
import eu.kanade.tachiyomi.databinding.EditEntryInfoBinding
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.launch
import logcat.LogPriority
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.core.common.util.system.logcat
import tachiyomi.domain.track.model.Track
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Backs the "Fill from tracker" button. [candidates] resolves the entry's bound trackers (manga and
 * novel each supply their own enumerator from the details ScreenModel), [fetch] pulls one tracker's
 * metadata. Null on [EntryEditInfoDialog] keeps the button disabled.
 */
class TrackerAutofill(
    val candidates: suspend () -> List<Pair<Track, Tracker>>,
    val fetch: suspend (Track, Tracker) -> TrackMangaMetadata,
)

/**
 * Neutral edit-info values shared by both content types. The caller seeds [EntryEditInfoDialog] with the
 * current effective values and maps the edited result back to its own storage (manga: the custom-info
 * overlay; novel: the in-row edit path).
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
 * The shared manga/novel edit-info editor: a Material dialog (ported from Komikku's EditMangaDialog)
 * whose scrolling content is a native [EditEntryInfoBinding] view hosted in an [AndroidView]. Native
 * EditText keeps the soft keyboard stable across field switches, which the pure-Compose form could not
 * on edge-to-edge Android 15+ (the keyboard blinked and the focused field hid behind it). This native
 * form is the one sanctioned exception to the Compose-native screen rule.
 *
 * The dialog owns Save/Cancel; the native form owns three resets (Reset info: non-tag fields back to
 * [source]; Reset tags: chips back to source.genre; Reset all: [onResetAll] clears every override), the
 * Fill-from-tracker slot ([autofill]), and the cover preview, which loads live from the URL field through
 * [coverModel] (the app's registered cover fetcher, so header-protected hosts work).
 */
@Composable
fun EntryEditInfoDialog(
    initial: EntryEditInfoUi,
    // The raw source values (no overlay). "Reset info" reverts the non-tag fields to these, "Reset tags"
    // reverts the chips to source.genre, both in-form so Save persists what's left.
    source: EntryEditInfoUi,
    // Cover-derived seed color, passed ungated by both content types so the editor always tints from the
    // cover regardless of the page's cover-theming preference. Null falls back to the app theme.
    seedColor: Color?,
    coverModel: (thumbnailUrl: String) -> Any?,
    onDismissRequest: () -> Unit,
    onSave: (EntryEditInfoUi) -> Unit,
    // "Reset all": clear every override and close (each caller clears its overlay).
    onResetAll: () -> Unit,
    // Null keeps the Fill-from-tracker button disabled (e.g. an unfavorited entry with no bound trackers).
    autofill: TrackerAutofill? = null,
) {
    TachiyomiTheme(seedColor = seedColor) {
        var binding by remember { mutableStateOf<EditEntryInfoBinding?>(null) }
        // The Compose parts (container, Save/Cancel) follow this theme; the native form views read the
        // Activity theme instead, so pass the scheme down and tint the chips + buttons to match.
        val colorScheme = MaterialTheme.colorScheme

        val scope = rememberCoroutineScope()
        var pickerTracks by remember { mutableStateOf<List<Pair<Track, Tracker>>?>(null) }

        // Fetch one tracker's metadata and fill the native fields; the toast/fill run on the composable's
        // scope, which resumes on the main thread so setText/setChips are safe.
        val fillFromTracker: (Track, Tracker) -> Unit = { track, tracker ->
            val b = binding
            if (b != null && autofill != null) {
                scope.launch {
                    val ctx = b.root.context
                    runCatching { autofill.fetch(track, tracker) }
                        .onSuccess { b.applyMetadata(it, colorScheme) }
                        .onFailure { e ->
                            logcat(LogPriority.ERROR, e) { "Fill from tracker failed (${tracker.name})" }
                            ctx.toast(ctx.stringResource(MR.strings.track_error, tracker.name, e.message ?: ""))
                        }
                }
            }
        }

        val onAutofillClick: (() -> Unit)? = if (autofill == null) {
            null
        } else {
            {
                scope.launch {
                    val ctx = binding?.root?.context ?: return@launch
                    val candidates = autofill.candidates()
                    when {
                        candidates.isEmpty() -> ctx.toast(ctx.stringResource(MR.strings.entry_not_tracked))
                        candidates.size == 1 -> fillFromTracker(candidates[0].first, candidates[0].second)
                        else -> pickerTracks = candidates
                    }
                }
            }
        }

        AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {
                TextButton(
                    onClick = {
                        val b = binding ?: return@TextButton
                        onSave(b.collect())
                        onDismissRequest()
                    },
                ) { Text(stringResource(MR.strings.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = onDismissRequest) { Text(stringResource(MR.strings.action_cancel)) }
            },
            text = {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                ) {
                    AndroidView(
                        factory = { context ->
                            EditEntryInfoBinding.inflate(LayoutInflater.from(context))
                                .also {
                                    binding = it
                                    it.setup(initial, source, coverModel, onResetAll, colorScheme, onAutofillClick)
                                }
                                .root
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )

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

private fun EditEntryInfoBinding.collect(): EntryEditInfoUi {
    val labels = statusLabels(root.context)
    return EntryEditInfoUi(
        title = title.text?.toString().orEmpty(),
        author = mangaAuthor.text?.toString().orEmpty(),
        artist = mangaArtist.text?.toString().orEmpty(),
        description = mangaDescription.text?.toString().orEmpty(),
        genre = mangaGenresTags.collectTags(),
        status = labels.indexOf(status.text.toString()).coerceAtLeast(0).toLong(),
        thumbnailUrl = thumbnailUrl.text?.toString().orEmpty(),
    )
}

/** Set the non-tag fields (status, title, author, artist, cover URL, description) from [ui]. */
private fun EditEntryInfoBinding.setInfoFields(ui: EntryEditInfoUi) {
    val labels = statusLabels(root.context)
    status.setText(labels[ui.status.toInt().coerceIn(0, labels.lastIndex)], false)
    title.setText(ui.title)
    mangaAuthor.setText(ui.author)
    mangaArtist.setText(ui.artist)
    thumbnailUrl.setText(ui.thumbnailUrl)
    mangaDescription.setText(ui.description)
}

// SManga and NovelStatusCode share the same 0-6 codes, so one option list and a 1:1 index serve both.
private fun statusLabels(context: android.content.Context) = listOf(
    MR.strings.label_default,
    MR.strings.ongoing,
    MR.strings.completed,
    MR.strings.licensed,
    MR.strings.publishing_finished,
    MR.strings.cancelled,
    MR.strings.on_hiatus,
).map { context.stringResource(it) }

private fun EditEntryInfoBinding.setup(
    initial: EntryEditInfoUi,
    source: EntryEditInfoUi,
    coverModel: (thumbnailUrl: String) -> Any?,
    onResetAll: () -> Unit,
    colorScheme: ColorScheme,
    onAutofillClick: (() -> Unit)?,
) {
    val context = root.context

    status.setAdapter(
        ArrayAdapter(context, android.R.layout.simple_list_item_1, statusLabels(context)),
    )
    setInfoFields(initial)

    mangaGenresTags.setChips(initial.genre, colorScheme)
    addTag.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_DONE) {
            val entered = addTag.text?.toString().orEmpty()
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            if (entered.isNotEmpty()) {
                mangaGenresTags.setChips((mangaGenresTags.collectTags() + entered).distinct(), colorScheme)
            }
            addTag.text?.clear()
            true
        } else {
            false
        }
    }

    resetInfo.text = context.stringResource(MR.strings.action_reset_info)
    resetTags.text = context.stringResource(MR.strings.action_reset_tags)
    resetAll.text = context.stringResource(MR.strings.action_reset_all)
    autofillFromTracker.text = context.stringResource(MR.strings.action_fill_from_tracker)
    autofillFromTracker.isEnabled = onAutofillClick != null
    onAutofillClick?.let { click -> autofillFromTracker.setOnClickListener { click() } }
    // Tint the tonal buttons to the cover-based scheme (the native views don't inherit the Compose theme).
    val buttonBg = ColorStateList.valueOf(colorScheme.secondaryContainer.toArgb())
    val buttonText = colorScheme.onSecondaryContainer.toArgb()
    listOf(resetInfo, resetTags, resetAll, autofillFromTracker).forEach { button ->
        button.backgroundTintList = buttonBg
        button.setTextColor(buttonText)
    }

    // Reset info: revert only the non-tag fields to source, leaving the chips (so Fill-from-tracker can
    // seed tags and Reset info drop the rest). Reset tags: chips back to source. Reset all: clear everything.
    resetInfo.setOnClickListener { setInfoFields(source) }
    resetTags.setOnClickListener { mangaGenresTags.setChips(source.genre, colorScheme) }
    resetAll.setOnClickListener { onResetAll() }

    // Stable preview of the effective cover, loaded once (Komikku loads the manga object the same way);
    // a typed URL takes effect on Save, not live.
    mangaCover.loadCover(coverModel(initial.thumbnailUrl))
}

/**
 * Fill the fields from a tracker's metadata. Text fields replace only when the tracker has a value;
 * genres merge into the existing chips (append-distinct) so autofill never wipes curated tags. The
 * cover preview is not refreshed here (a typed/filled URL takes effect on Save, matching [setup]).
 */
private fun EditEntryInfoBinding.applyMetadata(meta: TrackMangaMetadata, colorScheme: ColorScheme) {
    title.setTextIfNotBlank(meta.title)
    mangaAuthor.setTextIfNotBlank(meta.authors)
    mangaArtist.setTextIfNotBlank(meta.artists)
    thumbnailUrl.setTextIfNotBlank(meta.thumbnailUrl)
    mangaDescription.setTextIfNotBlank(meta.description)

    val genres = meta.genres.orEmpty().filter { it.isNotBlank() }
    if (genres.isNotEmpty()) {
        mangaGenresTags.setChips((mangaGenresTags.collectTags() + genres).distinct(), colorScheme)
    }
}

private fun EditText.setTextIfNotBlank(value: String?) {
    if (!value.isNullOrBlank()) setText(value)
}

private fun ImageView.loadCover(data: Any?) {
    load(data) {
        transformations(RoundedCornersTransformation(4.dpToPx.toFloat()))
    }
}

/** Rebuild the chip group from [tags]; each chip has a remove (close) icon, tinted to [colorScheme]. */
private fun ChipGroup.setChips(tags: List<String>, colorScheme: ColorScheme) {
    removeAllViews()
    val chipBg = ColorStateList.valueOf(colorScheme.secondaryContainer.toArgb())
    val chipFg = colorScheme.onSecondaryContainer.toArgb()
    tags.filter { it.isNotBlank() }.forEach { tag ->
        addView(
            Chip(context).apply {
                text = tag
                setTextColor(chipFg)
                chipBackgroundColor = chipBg
                isCloseIconVisible = true
                closeIconTint = ColorStateList.valueOf(chipFg)
                setOnCloseIconClickListener { this@setChips.removeView(this) }
            },
        )
    }
}

private fun ChipGroup.collectTags(): List<String> = (0 until childCount)
    .mapNotNull { index -> getChildAt(index) as? Chip }
    .map { it.text.toString() }
