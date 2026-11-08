package reikai.presentation.components

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.updatePadding
import ca.mpreg.webgpuviewer.renderer.GainmapInput
import ca.mpreg.webgpuviewer.renderer.Image
import ca.mpreg.webgpuviewer.viewer.ImagePage
import ca.mpreg.webgpuviewer.viewer.ImageViewer
import ca.mpreg.webgpuviewer.viewer.ImageViewerState
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.CachePolicy
import coil3.request.Disposable
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.tachiyomi.data.coil.ImageDecoder
import eu.kanade.tachiyomi.data.coil.newDecoder
import eu.kanade.tachiyomi.ui.reader.viewer.ReaderPageImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.MobileRotate
import mihon.icons.materialsymbols.rounded.Save
import mihon.icons.materialsymbols.rounded.Share
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A picture full screen, fitted and zoomable, closed by Back, by the X, or by a tap on the picture or around
 * it, with Share and Save. [picture] is a coil model. [canRotate] adds a button that turns the picture a
 * quarter and back, so a wide one fills an upright screen without turning the device. [actions] go after
 * Save. The cover dialog and a novel chapter's pictures both open in this.
 */
@Composable
fun PictureViewerDialog(
    picture: Any,
    snackbarHostState: SnackbarHostState,
    onShareClick: () -> Unit,
    onSaveClick: () -> Unit,
    onDismissRequest: () -> Unit,
    canRotate: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // Reading the graph in the composable body mirrors upstream's MangaCoverDialog, so this stays
    // diffable on a sync.
    val useNewRenderer = LocalContext.current.appGraph.basePreferences.highQualityRenderer.get()
    val view = LocalView.current
    var turned by remember(picture) { mutableStateOf(false) }

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
                                imageVector = MaterialSymbols.Rounded.Close,
                                contentDescription = stringResource(MR.strings.action_close),
                            )
                        }
                    }
                    Spacer(modifier = Modifier.weight(1f))
                    ActionsPill {
                        AppBarActions(
                            actions = listOfNotNull(
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_rotate),
                                    icon = MaterialSymbols.Rounded.MobileRotate,
                                    onClick = { turned = !turned },
                                ).takeIf { canRotate },
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_share),
                                    icon = MaterialSymbols.Rounded.Share,
                                    onClick = onShareClick,
                                ),
                                AppBar.Action(
                                    title = stringResource(MR.strings.action_save),
                                    icon = MaterialSymbols.Rounded.Save,
                                    onClick = onSaveClick,
                                ),
                            ),
                        )
                        actions()
                    }
                }
            },
        ) { contentPadding ->
            if (useNewRenderer) {
                // The viewer is laid out inside the bars rather than over them, and the page
                // is fitted to that box whenever it changes, so a fold or a rotation re-fits instead
                // of keeping a scale measured for the old window. The library has no fit of its own,
                // so the scale is ours to set, the way the reader's viewer sets its fit modes.
                val dismiss by rememberUpdatedState(onDismissRequest)
                // The viewer takes every touch, so a tap on the picture or around it closes here, as the old view's does.
                val state = remember { ImageViewerState().apply { onTap = { dismiss() } } }
                var page by remember { mutableStateOf<ImagePage?>(null) }
                var boxSize by remember { mutableStateOf(IntSize.Zero) }

                // The viewer draws on its own surface, which a layout rotation cannot turn, so the pixels turn.
                LaunchedEffect(picture, turned) {
                    val request = ImageRequest.Builder(view.context)
                        .data(picture)
                        .size(Size.ORIGINAL)
                        .memoryCachePolicy(CachePolicy.DISABLED)
                        .newDecoder(true)
                        .build()
                    val result = view.context.imageLoader.execute(request) as? SuccessResult ?: return@LaunchedEffect
                    val res = result.image as? ImageDecoder.DecodeResultImage ?: return@LaunchedEffect
                    // Held, then freed, around the upload: its buffer alone doesn't keep
                    // the frame's native pixels alive.
                    page = res.frame.use {
                        withContext(Dispatchers.Default) {
                            val pixels = if (turned) quarterTurn(res.image, res.width, res.height) else res.image
                            ImagePage.ImageSingle(
                                Image(
                                    pixels ?: res.image,
                                    if (turned && pixels != null) res.height else res.width,
                                    if (turned && pixels != null) res.width else res.height,
                                    createMipMaps = true,
                                    backgroundColor = 0,
                                    hdr = res.isHdr,
                                    hdrHeadroom = res.hdrHeadroom,
                                    // The gain map stays the picture's way up, so a turned picture goes without it.
                                    gainmap = res.gainmap?.takeIf { !turned }?.let {
                                        GainmapInput(
                                            pixels = it.pixels,
                                            width = it.width,
                                            height = it.height,
                                            channels = it.channels,
                                            gamma = it.gamma,
                                            minContentBoost = it.minContentBoost,
                                            maxContentBoost = it.maxContentBoost,
                                            offsetSdr = it.offsetSdr,
                                            offsetHdr = it.offsetHdr,
                                        )
                                    },
                                ),
                            )
                        }
                    }
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
            // Each update loads again, and a lambda target is not cancelled by the next load as a view's is,
            // so a slower turned load finishing last would draw the picture the wrong way up.
            val load = remember { arrayOfNulls<Disposable>(1) }

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
                        val turn = turned
                        val request = ImageRequest.Builder(view.context)
                            .data(picture)
                            .size(Size.ORIGINAL)
                            .memoryCachePolicy(CachePolicy.DISABLED)
                            // A software copy, which a turn can draw from.
                            .allowHardware(!turn)
                            .target { image ->
                                val drawable = image.asDrawable(view.context.resources)
                                // Copy bitmap in case it came from memory cache
                                // Because SSIV needs to thoroughly read the image
                                val copy = (drawable as? BitmapDrawable)
                                    ?.bitmap
                                    ?.let { if (turn) it.turnedQuarter() else it }
                                    ?.copy(Bitmap.Config.HARDWARE, false)
                                    ?.toDrawable(view.context.resources)
                                    ?: drawable
                                view.setImage(copy, ReaderPageImageView.Config(zoomDuration = 500))
                            }
                            .build()
                        load[0]?.dispose()
                        load[0] = view.context.imageLoader.enqueue(request)

                        view.updatePadding(top = statusBarPaddingPx, bottom = bottomPaddingPx)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

private fun Bitmap.turnedQuarter(): Bitmap =
    Bitmap.createBitmap(this, 0, 0, width, height, Matrix().apply { postRotate(90f) }, true)

/**
 * [pixels], [width] by [height] and tightly packed, turned a quarter clockwise. Null when the buffer is not a
 * whole number of bytes a pixel, which a padded row would leave.
 */
private fun quarterTurn(pixels: ByteBuffer, width: Int, height: Int): ByteBuffer? {
    val count = width.toLong() * height
    if (count <= 0 || pixels.capacity() % count != 0L) return null
    val bytes = (pixels.capacity() / count).toInt()
    val out = ByteBuffer.allocateDirect(pixels.capacity()).order(ByteOrder.nativeOrder())
    val pixel = ByteArray(bytes)
    for (y in 0 until height) {
        for (x in 0 until width) {
            pixels.position((y * width + x) * bytes)
            pixels.get(pixel)
            out.position(((x * height) + (height - 1 - y)) * bytes)
            out.put(pixel)
        }
    }
    pixels.rewind()
    out.rewind()
    return out
}

@Composable
private fun ActionsPill(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .clip(MaterialTheme.shapes.extraLarge)
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.95f)),
    ) {
        content()
    }
}
