package reikai.presentation.details

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.ImageView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import coil3.load
import coil3.request.transformations
import coil3.transform.RoundedCornersTransformation
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.tachiyomi.databinding.EditEntryInfoBinding
import eu.kanade.tachiyomi.util.system.dpToPx
import tachiyomi.core.common.i18n.stringResource
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

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
 * The dialog owns Save/Cancel; the native form owns Reset-tags (reverts to [sourceGenre]), Reset-info
 * ([onResetInfo], clears every override) and the inert Fill-from-tracker slot. The cover preview loads
 * live from the URL field through [coverModel] (the app's registered cover fetcher, so header-protected
 * hosts work).
 */
@Composable
fun EntryEditInfoDialog(
    initial: EntryEditInfoUi,
    sourceGenre: List<String>,
    // Cover-derived seed color, passed ungated by both content types so the editor always tints from the
    // cover regardless of the page's cover-theming preference. Null falls back to the app theme.
    seedColor: Color?,
    coverModel: (thumbnailUrl: String) -> Any?,
    onDismissRequest: () -> Unit,
    onSave: (EntryEditInfoUi) -> Unit,
    onResetInfo: () -> Unit,
) {
    TachiyomiTheme(seedColor = seedColor) {
        var binding by remember { mutableStateOf<EditEntryInfoBinding?>(null) }
        // The Compose parts (container, Save/Cancel) follow this theme; the native form views read the
        // Activity theme instead, so pass the scheme down and tint the chips + buttons to match.
        val colorScheme = MaterialTheme.colorScheme

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
                                    it.setup(initial, sourceGenre, coverModel, onResetInfo, colorScheme)
                                }
                                .root
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }
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
    sourceGenre: List<String>,
    coverModel: (thumbnailUrl: String) -> Any?,
    onResetInfo: () -> Unit,
    colorScheme: ColorScheme,
) {
    val context = root.context

    val labels = statusLabels(context)
    status.setAdapter(ArrayAdapter(context, android.R.layout.simple_list_item_1, labels))
    status.setText(labels[initial.status.toInt().coerceIn(0, labels.lastIndex)], false)

    title.setText(initial.title)
    mangaAuthor.setText(initial.author)
    mangaArtist.setText(initial.artist)
    thumbnailUrl.setText(initial.thumbnailUrl)
    mangaDescription.setText(initial.description)

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

    resetTags.text = "Reset tags"
    resetInfo.text = context.stringResource(MR.strings.action_reset)
    autofillFromTracker.text = "Fill from tracker"
    // Fill-from-tracker is wired in a later stage; the slot is present but inert.
    autofillFromTracker.isEnabled = false
    // Tint the tonal buttons to the cover-based scheme (the native views don't inherit the Compose theme).
    val buttonBg = ColorStateList.valueOf(colorScheme.secondaryContainer.toArgb())
    val buttonText = colorScheme.onSecondaryContainer.toArgb()
    listOf(resetTags, resetInfo, autofillFromTracker).forEach { button ->
        button.backgroundTintList = buttonBg
        button.setTextColor(buttonText)
    }

    resetTags.setOnClickListener { mangaGenresTags.setChips(sourceGenre, colorScheme) }
    resetInfo.setOnClickListener { onResetInfo() }

    // Stable preview of the effective cover, loaded once (Komikku loads the manga object the same way);
    // a typed URL takes effect on Save, not live.
    mangaCover.loadCover(coverModel(initial.thumbnailUrl))
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
