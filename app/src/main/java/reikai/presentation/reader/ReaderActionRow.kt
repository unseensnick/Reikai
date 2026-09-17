package reikai.presentation.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Explore
import mihon.icons.materialsymbols.rounded.FormatBold
import mihon.icons.materialsymbols.rounded.FormatListNumbered
import mihon.icons.materialsymbols.rounded.Palette
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Settings
import mihon.icons.materialsymbols.rounded.Share
import mihon.icons.materialsymbols.rounded.SkipNext
import mihon.icons.materialsymbols.rounded.SkipPrevious
import reikai.presentation.icons.FormatSize
import reikai.presentation.icons.Lightbulb
import reikai.presentation.icons.RecordVoiceOver
import reikai.presentation.icons.ReikaiIcons
import reikai.presentation.icons.SwipeVertical
import reikai.presentation.icons.VerticalAlignTop
import reikai.presentation.icons.VolumeUp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** The chapter buttons the bar takes over while the progress navigator is hidden. */
data class ReaderChapterStep(
    val isRtl: Boolean,
    val hasPrevious: Boolean,
    val hasNext: Boolean,
    val onPrevious: () -> Unit,
    val onNext: () -> Unit,
)

/**
 * The reader's bottom action row, drawn for both content types by the one reader host. Which buttons appear,
 * and in what order, is [enabledButtons]: [ReaderBottomButton.ordered] has already dropped the buttons the
 * open content type does not offer, and always keeps the Settings gear. A nullable callback is an action the
 * open chapter may not support, and its button is hidden while it is null. [chapterStep] puts the chapter
 * buttons at the two ends, pointing the way the navigator's would.
 */
@Composable
fun ReaderActionRow(
    enabledButtons: List<ReaderBottomButton>,
    onClickChapterList: () -> Unit,
    onClickWebView: (() -> Unit)?,
    onClickBrowser: (() -> Unit)?,
    onClickShare: (() -> Unit)?,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    onClickSettings: () -> Unit,
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    keepScreenOn: Boolean,
    onClickKeepScreenOn: () -> Unit,
    onClickScrollToTop: () -> Unit,
    modifier: Modifier = Modifier,
    // Novel toggles. The active flag tints the icon.
    autoScrollActive: Boolean = false,
    onClickAutoScroll: (() -> Unit)? = null,
    bionicActive: Boolean = false,
    onClickBionic: (() -> Unit)? = null,
    // Novel pickers: open a small chooser (theme / text size), like the rotation button.
    onClickTheme: (() -> Unit)? = null,
    onClickTextSize: (() -> Unit)? = null,
    // Novel: tap shows or hides the read-aloud controls, long-press stops speech.
    readAloudControlsVisible: Boolean = false,
    onClickReadAloud: (() -> Unit)? = null,
    onLongClickReadAloud: () -> Unit = {},
    chapterStep: ReaderChapterStep? = null,
) {
    Row(
        modifier = modifier
            // Swallow taps so pressing empty bar space doesn't toggle the reader chrome.
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        chapterStep?.let { ChapterStepButton(it, leading = true) }
        // Exhaustive, so a button added to the enum cannot be left undrawn here.
        enabledButtons.forEach { button ->
            when (button) {
                ReaderBottomButton.ViewChapters -> IconButton(onClick = onClickChapterList) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.FormatListNumbered,
                        contentDescription = stringResource(MR.strings.chapters),
                    )
                }

                ReaderBottomButton.WebView -> if (onClickWebView != null) {
                    IconButton(onClick = onClickWebView) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Public,
                            contentDescription = stringResource(MR.strings.action_open_in_web_view),
                        )
                    }
                }

                ReaderBottomButton.Browser -> if (onClickBrowser != null) {
                    IconButton(onClick = onClickBrowser) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Explore,
                            contentDescription = stringResource(MR.strings.action_open_in_browser),
                        )
                    }
                }

                ReaderBottomButton.Share -> if (onClickShare != null) {
                    IconButton(onClick = onClickShare) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Share,
                            contentDescription = stringResource(MR.strings.action_share),
                        )
                    }
                }

                ReaderBottomButton.ReadingMode -> IconButton(onClick = onClickReadingMode) {
                    Icon(
                        painter = painterResource(readingMode.iconRes),
                        contentDescription = stringResource(MR.strings.viewer),
                    )
                }

                ReaderBottomButton.Rotation -> IconButton(onClick = onClickOrientation) {
                    Icon(
                        imageVector = orientation.icon,
                        contentDescription = stringResource(MR.strings.rotation_type),
                    )
                }

                ReaderBottomButton.CropBorders -> IconButton(onClick = onClickCropBorder) {
                    Icon(
                        painter = painterResource(
                            if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp,
                        ),
                        contentDescription = stringResource(MR.strings.pref_crop_borders),
                    )
                }

                ReaderBottomButton.Autoscroll -> if (onClickAutoScroll != null) {
                    ToggleActionButton(
                        onClick = onClickAutoScroll,
                        icon = ReikaiIcons.SwipeVertical,
                        description = stringResource(MR.strings.pref_auto_scroll),
                        active = autoScrollActive,
                    )
                }

                ReaderBottomButton.KeepScreenOn -> ToggleActionButton(
                    onClick = onClickKeepScreenOn,
                    icon = ReikaiIcons.Lightbulb,
                    description = stringResource(MR.strings.pref_keep_screen_on),
                    active = keepScreenOn,
                )

                ReaderBottomButton.BionicReading -> if (onClickBionic != null) {
                    ToggleActionButton(
                        onClick = onClickBionic,
                        icon = MaterialSymbols.Rounded.FormatBold,
                        description = stringResource(MR.strings.pref_bionic_reading),
                        active = bionicActive,
                    )
                }

                ReaderBottomButton.Theme -> if (onClickTheme != null) {
                    IconButton(onClick = onClickTheme) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Palette,
                            contentDescription = stringResource(MR.strings.pref_category_theme),
                        )
                    }
                }

                ReaderBottomButton.TextSize -> if (onClickTextSize != null) {
                    IconButton(onClick = onClickTextSize) {
                        Icon(
                            imageVector = ReikaiIcons.FormatSize,
                            contentDescription = stringResource(MR.strings.pref_reader_text_size),
                        )
                    }
                }

                ReaderBottomButton.ScrollToTop -> IconButton(onClick = onClickScrollToTop) {
                    Icon(
                        imageVector = ReikaiIcons.VerticalAlignTop,
                        contentDescription = stringResource(MR.strings.action_scroll_to_top),
                    )
                }

                ReaderBottomButton.ReadAloud -> if (onClickReadAloud != null) {
                    ReadAloudActionButton(
                        controlsVisible = readAloudControlsVisible,
                        onClick = onClickReadAloud,
                        onLongClick = onLongClickReadAloud,
                    )
                }

                ReaderBottomButton.Settings -> IconButton(onClick = onClickSettings) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Settings,
                        contentDescription = stringResource(MR.strings.action_settings),
                    )
                }
            }
        }
        chapterStep?.let { ChapterStepButton(it, leading = false) }
    }
}

/** The navigator's previous or next chapter button, swapped for a right-to-left reader as the navigator does. */
@Composable
private fun ChapterStepButton(step: ReaderChapterStep, leading: Boolean) {
    val previous = leading != step.isRtl
    IconButton(
        onClick = if (previous) step.onPrevious else step.onNext,
        enabled = if (previous) step.hasPrevious else step.hasNext,
    ) {
        Icon(
            imageVector = if (leading) MaterialSymbols.Rounded.SkipPrevious else MaterialSymbols.Rounded.SkipNext,
            contentDescription = stringResource(
                if (previous) MR.strings.action_previous_chapter else MR.strings.action_next_chapter,
            ),
        )
    }
}

/** A container rather than a tint while the controls show, since they are a panel this button opened. */
@Composable
private fun ReadAloudActionButton(
    controlsVisible: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .size(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(if (controlsVisible) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick,
                onLongClickLabel = stringResource(MR.strings.tts_stop),
                role = Role.Button,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = if (controlsVisible) ReikaiIcons.VolumeUp else ReikaiIcons.RecordVoiceOver,
            contentDescription = stringResource(MR.strings.pref_category_read_aloud),
            tint = if (controlsVisible) MaterialTheme.colorScheme.onPrimaryContainer else LocalContentColor.current,
        )
    }
}

/** A bottom-bar button for a boolean reader setting; tints its icon while the setting is on. */
@Composable
private fun ToggleActionButton(
    onClick: () -> Unit,
    icon: ImageVector,
    description: String,
    active: Boolean,
) {
    IconButton(onClick = onClick) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = if (active) MaterialTheme.colorScheme.primary else LocalContentColor.current,
        )
    }
}
