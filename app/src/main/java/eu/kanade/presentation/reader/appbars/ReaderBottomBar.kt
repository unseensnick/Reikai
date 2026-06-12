package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
// RK -->
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
// RK <--
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.R
// RK -->
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
// RK <--
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderBottomBar(
    // RK -->
    enabledButtons: Set<String>,
    // RK <--
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // RK -->
    onClickChapterList: () -> Unit,
    onClickWebView: (() -> Unit)?,
    onClickBrowser: (() -> Unit)?,
    onClickShare: (() -> Unit)?,
    // RK <--
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // RK --> configurable bottom buttons (Y9); the settings gear stays always-on
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

        if (ReaderBottomButton.ReadingMode.isIn(enabledButtons)) {
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

        if (ReaderBottomButton.CropBorders.isIn(enabledButtons)) {
            IconButton(onClick = onClickCropBorder) {
                Icon(
                    painter = painterResource(
                        if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp,
                    ),
                    contentDescription = stringResource(MR.strings.pref_crop_borders),
                )
            }
        }
        // RK <--

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
            )
        }
    }
}
