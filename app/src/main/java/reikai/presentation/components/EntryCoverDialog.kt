package reikai.presentation.components

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.updatePadding
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.viewer.ImagePage
import ca.mpreg.webgpuviewer.viewer.ImageViewer
import ca.mpreg.webgpuviewer.viewer.ImageViewerState
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Size
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.manga.EditCoverAction
import eu.kanade.tachiyomi.data.coil.ImageDecoder
import eu.kanade.tachiyomi.data.coil.newDecoder
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import mihon.app.di.appGraph
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication

/**
 * Full-cover dialog shared by manga and novels: a zoomable full-resolution cover with share / save /
 * set-custom-cover / delete-custom-cover actions. [cover] is a coil model (a `Manga` or a
 * `reikai.data.coil.NovelCover`), so each content type feeds its own object; [onEditClick] is null when
 * the entry isn't in the library (hides edit/delete). Replaces the twin `MangaCoverDialog` /
 * `NovelCoverDialog`.
 */
@Composable
fun EntryCoverDialog(
    cover: Any,
    isCustomCover: Boolean,
    snackbarHostState: SnackbarHostState,
    onShareClick: () -> Unit,
    onSaveClick: () -> Unit,
    onEditClick: ((EditCoverAction) -> Unit)?,
    onDismissRequest: () -> Unit,
) {
    // Reading the graph in the composable body mirrors upstream's MangaCoverDialog, so this stays
    // diffable on a sync.
    val useNewRenderer = LocalContext.current.appGraph.basePreferences.highQualityRenderer.get()
    val view = LocalView.current

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
        ),
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            containerColor = Color.Transparent,
            bottomBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp)
                        .navigationBarsPadding(),
                ) {
                    ActionsPill {
                        IconButton(onClick = onDismissRequest) {
                            Icon(
                                imageVector = Icons.Outlined.Close,
                                contentDescription = stringResource(MR.strings.action_close),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    ActionsPill {
                        AppBarActions(
                            actions = listOf(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_share),
                                    icon = Icons.Outlined.Share,
                                    onClick = onShareClick,
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_save),
                                    icon = Icons.Outlined.Save,
                                    onClick = onSaveClick,
                                ),
                            ),
                        )
                        if (onEditClick != null) {
                            Box {
                                var expanded by remember { mutableStateOf(false) }
                                IconButton(
                                    onClick = {
                                        if (isCustomCover) {
                                            expanded = true
                                        } else {
                                            onEditClick(EditCoverAction.EDIT)
                                        }
                                    },
                                ) {
                                    Icon(
                                        imageVector = Icons.Outlined.Edit,
                                        contentDescription = stringResource(MR.strings.action_edit_cover),
                                    )
                                }
                                DropdownMenu(
                                    expanded = expanded,
                                    onDismissRequest = { expanded = false },
                                    offset = DpOffset(8.dp, 0.dp),
                                ) {
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(MR.strings.action_edit)) },
                                        onClick = {
                                            onEditClick(EditCoverAction.EDIT)
                                            expanded = false
                                        },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(text = stringResource(MR.strings.action_delete)) },
                                        onClick = {
                                            onEditClick(EditCoverAction.DELETE)
                                            expanded = false
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            },
        ) { contentPadding ->
            if (useNewRenderer) {
                // The viewer is laid out inside the bars rather than over them, and the page
                // is fitted to that box whenever it changes, so a fold or a rotation re-fits instead
                // of keeping a scale measured for the old window. The library has no fit of its own,
                // so the scale is ours to set, the way the reader's viewer sets its fit modes.
                val state = remember { ImageViewerState() }
                var page by remember { mutableStateOf<ImagePage?>(null) }
                var boxSize by remember { mutableStateOf(IntSize.Zero) }

                LaunchedEffect(cover) {
                    ImageRequest.Builder(view.context)
                        .data(cover)
                        .size(Size.ORIGINAL)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .newDecoder(true)
                        .target { result ->
                            val res = (result as ImageDecoder.DecodeResultImage).res
                            page = runBlocking(Dispatchers.Default) {
                                ImagePage.ImageSingle(
                                    Image(
                                        res.image,
                                        res.width,
                                        res.height,
                                        createMipMaps = true,
                                        backgroundColor = 0,
                                    ),
                                )
                            }
                        }
                        .build()
                        .let(view.context.imageLoader::enqueue)
                }

                LaunchedEffect(page, boxSize) {
                    val loaded = page ?: return@LaunchedEffect
                    if (boxSize.width > 0 && boxSize.height > 0 &&
                        loaded.trimWidth > 0 && loaded.trimHeight > 0
                    ) {
                        val fit = minOf(
                            boxSize.width.toFloat() / loaded.trimWidth,
                            boxSize.height.toFloat() / loaded.trimHeight,
                        )
                        // webgpuviewer 38 dropped the nullable homeScaleOverride that used to
                        // suppress the library's own fit, so the current scale is applied here
                        // too, the way the reader's viewer pairs the two.
                        loaded.homeScale = fit
                        loaded.scale = fit
                        loaded.minScale = minOf(loaded.minScale, fit)
                    }
                    state.fetchPage = { index -> loaded.takeIf { index == 0 } }
                    state.invalidate()
                }

                ImageViewer(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .onSizeChanged { boxSize = it },
                    state = state,
                )
                return@Scaffold
            }

            val statusBarPaddingPx = with(LocalDensity.current) { contentPadding.calculateTopPadding().roundToPx() }
            val bottomPaddingPx = with(LocalDensity.current) { contentPadding.calculateBottomPadding().roundToPx() }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickableNoIndication(onClick = onDismissRequest),
            ) {
                AndroidView(
                    factory = {
                        ReaderPageImageView(it).apply {
                            onViewClicked = onDismissRequest
                            clipToPadding = false
                            clipChildren = false
                        }
                    },
                    update = { view ->
                        val request = ImageRequest.Builder(view.context)
                            .data(cover)
                            .size(Size.ORIGINAL)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            .target { image ->
                                val drawable = image.asDrawable(view.context.resources)
                                // Copy bitmap in case it came from memory cache
                                // Because SSIV needs to thoroughly read the image
                                val copy = (drawable as? BitmapDrawable)
                                    ?.bitmap
                                    ?.copy(Bitmap.Config.HARDWARE, false)
                                    ?.toDrawable(view.context.resources)
                                    ?: drawable
                                view.setImage(copy, ReaderPageImageView.Config(zoomDuration = 500))
                            }
                            .build()
                        view.context.imageLoader.enqueue(request)

                        view.updatePadding(top = statusBarPaddingPx, bottom = bottomPaddingPx)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ActionsPill(content: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f)),
    ) {
        content()
    }
}
