package reikai.presentation.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FormatBold
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.FormatSize
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.SwipeVertical
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Shared reader bottom action row for the manga and novel readers. The manga [ReaderBottomBar] and the
 * novel reader both delegate here, so the two bottom bars can't drift. Which buttons appear is driven by
 * [enabledButtons] (the [ReaderBottomButton] selection), and each button also gates on its callback being
 * non-null, so per-type buttons (manga: reading mode / crop; novel: auto-scroll / keep-screen-on / bionic)
 * simply pass null from the other reader. The Settings gear is always shown.
 */
@Composable
fun ReaderActionRow(
    enabledButtons: Set<String>,
    onClickChapterList: () -> Unit,
    onClickWebView: (() -> Unit)?,
    onClickBrowser: (() -> Unit)?,
    onClickShare: (() -> Unit)?,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    onClickSettings: () -> Unit,
    modifier: Modifier = Modifier,
    // Manga-only: null on the novel reader.
    readingMode: ReadingMode? = null,
    onClickReadingMode: (() -> Unit)? = null,
    cropEnabled: Boolean = false,
    onClickCropBorder: (() -> Unit)? = null,
    // Novel-only toggles: null on the manga reader. The active flag tints the icon.
    autoScrollActive: Boolean = false,
    onClickAutoScroll: (() -> Unit)? = null,
    keepScreenOn: Boolean = false,
    onClickKeepScreenOn: (() -> Unit)? = null,
    bionicActive: Boolean = false,
    onClickBionic: (() -> Unit)? = null,
    // Novel-only pickers: open a small chooser (theme / text size), like the rotation button.
    onClickTheme: (() -> Unit)? = null,
    onClickTextSize: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            // Swallow taps so pressing empty bar space doesn't toggle the reader chrome.
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (ReaderBottomButton.ViewChapters.isIn(enabledButtons)) {
            IconButton(onClick = onClickChapterList) {
                Icon(
                    imageVector = Icons.Outlined.FormatListNumbered,
                    contentDescription = stringResource(MR.strings.chapters),
                )
            }
        }

        if (ReaderBottomButton.WebView.isIn(enabledButtons) && onClickWebView != null) {
            IconButton(onClick = onClickWebView) {
                Icon(
                    imageVector = Icons.Outlined.Public,
                    contentDescription = stringResource(MR.strings.action_open_in_web_view),
                )
            }
        }

        if (ReaderBottomButton.Browser.isIn(enabledButtons) && onClickBrowser != null) {
            IconButton(onClick = onClickBrowser) {
                Icon(
                    imageVector = Icons.Outlined.Explore,
                    contentDescription = stringResource(MR.strings.action_open_in_browser),
                )
            }
        }

        if (ReaderBottomButton.Share.isIn(enabledButtons) && onClickShare != null) {
            IconButton(onClick = onClickShare) {
                Icon(
                    imageVector = Icons.Outlined.Share,
                    contentDescription = stringResource(MR.strings.action_share),
                )
            }
        }

        if (ReaderBottomButton.ReadingMode.isIn(enabledButtons) && onClickReadingMode != null && readingMode != null) {
            IconButton(onClick = onClickReadingMode) {
                Icon(
                    painter = painterResource(readingMode.iconRes),
                    contentDescription = stringResource(MR.strings.viewer),
                )
            }
        }

        if (ReaderBottomButton.Rotation.isIn(enabledButtons)) {
            IconButton(onClick = onClickOrientation) {
                Icon(
                    imageVector = orientation.icon,
                    contentDescription = stringResource(MR.strings.rotation_type),
                )
            }
        }

        if (ReaderBottomButton.CropBorders.isIn(enabledButtons) && onClickCropBorder != null) {
            IconButton(onClick = onClickCropBorder) {
                Icon(
                    painter = painterResource(
                        if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp,
                    ),
                    contentDescription = stringResource(MR.strings.pref_crop_borders),
                )
            }
        }

        if (ReaderBottomButton.Autoscroll.isIn(enabledButtons) && onClickAutoScroll != null) {
            ToggleActionButton(
                onClick = onClickAutoScroll,
                icon = Icons.Outlined.SwipeVertical,
                description = stringResource(MR.strings.pref_auto_scroll),
                active = autoScrollActive,
            )
        }

        if (ReaderBottomButton.KeepScreenOn.isIn(enabledButtons) && onClickKeepScreenOn != null) {
            ToggleActionButton(
                onClick = onClickKeepScreenOn,
                icon = Icons.Outlined.Lightbulb,
                description = stringResource(MR.strings.pref_keep_screen_on),
                active = keepScreenOn,
            )
        }

        if (ReaderBottomButton.BionicReading.isIn(enabledButtons) && onClickBionic != null) {
            ToggleActionButton(
                onClick = onClickBionic,
                icon = Icons.Outlined.FormatBold,
                description = stringResource(MR.strings.pref_bionic_reading),
                active = bionicActive,
            )
        }

        if (ReaderBottomButton.Theme.isIn(enabledButtons) && onClickTheme != null) {
            IconButton(onClick = onClickTheme) {
                Icon(
                    imageVector = Icons.Outlined.Palette,
                    contentDescription = stringResource(MR.strings.pref_category_theme),
                )
            }
        }

        if (ReaderBottomButton.TextSize.isIn(enabledButtons) && onClickTextSize != null) {
            IconButton(onClick = onClickTextSize) {
                Icon(
                    imageVector = Icons.Outlined.FormatSize,
                    contentDescription = stringResource(MR.strings.pref_reader_text_size),
                )
            }
        }

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
            )
        }
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
