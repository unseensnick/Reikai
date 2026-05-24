package yokai.presentation.manga.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImagePainter
import dev.icerock.moko.resources.compose.stringResource
import dev.icerock.moko.resources.desc.Utils
import yokai.i18n.MR
import yokai.presentation.library.components.LazyLibraryStaggeredGrid
import yokai.domain.manga.models.MangaCover as MangaCoverModel

@Composable
fun BadgeSegments(
    lang: String? = null,
    unreadCount: Int = 0,
    downloadCount: Int = 0,
    /**
     * Render the unread badge as a small dot instead of a count chip. Matches the legacy
     * `unreadBadgeType = 2` mode. When true, [unreadCount] is only used as a presence check;
     * the dot itself has no text.
     */
    unreadDot: Boolean = false,
    extraBadgeSegments: List<BadgeSegment> = listOf(),
) = buildList {
    val context = LocalContext.current

    // Each badge segment is independent: previously the download / unread segments were nested
    // inside the language-flag block, so turning the language badge off (or running on a manga
    // whose source language had no flag asset) silently dropped the download and unread badges
    // too. Lifting them out matches the legacy behaviour where each badge has its own switch.
    if (!lang.isNullOrBlank()) {
        val resources = Utils.resourcesForContext(context)
        val flagId = resources.getIdentifier(
            "ic_flag_${lang.replace("-", "_")}",
            "drawable",
            context.packageName,
        ).takeIf { it != 0 } ?: (
            if (lang.contains("-")) {
                resources.getIdentifier(
                    "ic_flag_${lang.split("-").first()}",
                    "drawable",
                    context.packageName,
                ).takeIf { it != 0 }
            } else {
                null
            }
            )
        if (flagId != null) {
            add(
                BadgeSegment(
                    fillEntireSegment = true,
                    content = {
                        Image(
                            painter = painterResource(id = flagId),
                            contentDescription = "lang: $lang",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxHeight()
                                .wrapContentWidth()
                                .aspectRatio(3f / 2f)
                        )
                    }
                )
            )
        }
    }

    if (downloadCount > 0) {
        add(
            BadgeSegment.text(
                backgroundColor = MaterialTheme.colorScheme.tertiary,
                text = downloadCount.toString(),
                textColor = MaterialTheme.colorScheme.onTertiary,
                contentDescription = stringResource(MR.strings._downloaded, downloadCount),
            )
        )
    }

    if (unreadCount > 0) {
        add(
            if (unreadDot) {
                BadgeSegment.dot(color = MaterialTheme.colorScheme.secondary)
            } else {
                BadgeSegment.text(
                    backgroundColor = MaterialTheme.colorScheme.secondary,
                    text = unreadCount.toString(),
                    textColor = MaterialTheme.colorScheme.onSecondary,
                    contentDescription = stringResource(MR.strings._unread, unreadCount),
                )
            }
        )
    }
} + extraBadgeSegments

@Composable
fun MangaComfortableGridItem(
    coverData: MangaCoverModel,
    title: String,
    lang: String? = null,
    unreadCount: Int = 0,
    downloadCount: Int = 0,
    badgeSegments: List<BadgeSegment> = listOf(),
    isSelected: Boolean = false,
    showOutline: Boolean = false,
    /**
     * Aspect ratio applied to the cover box. Default [MangaCoverRatio.BOOK] matches a 2:3 cover
     * thumbnail, which is what fixed-column grids and most browse surfaces need. Pass `null`
     * (staggered grid + uniform-grid pref off) to let the cover render at its intrinsic ratio.
     */
    coverAspectRatio: Float? = MangaCoverRatio.BOOK,
    /** When true, the unread badge renders as a dot instead of a chapter count. */
    unreadDot: Boolean = false,
    onClickContinueReading: (() -> Unit)? = null,
    showLoadingIndicator: Boolean = true,
) {
    Column {
        MangaGridCover(
            aspectRatio = coverAspectRatio,
            border = if (showOutline) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
            cover = {
                Box {
                    if (showLoadingIndicator) {
                        // Tracks Coil state to overlay a spinner. Cheap per item, but with
                        // hundreds of covers loading on cold start each state transition adds a
                        // recompose, so callers (e.g., the library) opt out via showLoadingIndicator.
                        var isLoading by remember { mutableStateOf(false) }
                        MangaCover(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (isSelected) 0.34f else 1.0f),
                            data = coverData,
                            onState = { state ->
                                isLoading = state is AsyncImagePainter.State.Loading
                            },
                        )
                        if (isLoading) {
                            LoadingIndicator(modifier = Modifier.align(Alignment.Center))
                        }
                    } else {
                        MangaCover(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (isSelected) 0.34f else 1.0f),
                            data = coverData,
                        )
                    }
                }
            },
            badgeSegments = BadgeSegments(
                lang = lang,
                unreadCount = unreadCount,
                downloadCount = downloadCount,
                unreadDot = unreadDot,
                extraBadgeSegments = badgeSegments,
            ),
            content = {
                if (onClickContinueReading != null) {
                    ContinueReadingButton(
                        modifier = Modifier.align(Alignment.BottomEnd),
                        onClick = onClickContinueReading,
                    )
                }
            },
        )
        GridItemTitle(
            modifier = Modifier.padding(4.dp),
            title = title,
            style = MaterialTheme.typography.titleSmall.copy(
                color = MaterialTheme.colorScheme.onBackground,
            ),
            minLines = 2,
        )
    }
}

@Composable
fun MangaCompactGridItem(
    coverData: MangaCoverModel,
    title: String,
    lang: String? = null,
    unreadCount: Int = 0,
    downloadCount: Int = 0,
    badgeSegments: List<BadgeSegment> = listOf(),
    isSelected: Boolean = false,
    showOutline: Boolean = false,
    /**
     * When false, the gradient title overlay at the bottom of the cover is suppressed; the
     * cell shows just the cover image. Library uses this for LAYOUT_COVER_ONLY_GRID to match
     * the legacy view where `compactTitle` + `gradient` are hidden in cover-only mode.
     */
    showTitle: Boolean = true,
    /** See [MangaComfortableGridItem.coverAspectRatio]. */
    coverAspectRatio: Float? = MangaCoverRatio.BOOK,
    /** See [MangaComfortableGridItem.unreadDot]. */
    unreadDot: Boolean = false,
    onClickContinueReading: (() -> Unit)? = null,
    showLoadingIndicator: Boolean = true,
) {
    MangaGridCover(
        aspectRatio = coverAspectRatio,
        border = if (showOutline) BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
        cover = {
            Box {
                if (showLoadingIndicator) {
                    var isLoading by remember { mutableStateOf(false) }
                    MangaCover(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (isSelected) 0.34f else 1.0f),
                        data = coverData,
                        onState = { state ->
                            isLoading = state is AsyncImagePainter.State.Loading
                        },
                    )
                    if (isLoading) {
                        LoadingIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                } else {
                    MangaCover(
                        modifier = Modifier
                            .fillMaxWidth()
                            .alpha(if (isSelected) 0.34f else 1.0f),
                        data = coverData,
                    )
                }
            }
        },
        badgeSegments = BadgeSegments(
            lang = lang,
            unreadCount = unreadCount,
            downloadCount = downloadCount,
            unreadDot = unreadDot,
            extraBadgeSegments = badgeSegments,
        ),
        content = {
            if (onClickContinueReading != null) {
                ContinueReadingButton(
                    modifier = Modifier.align(Alignment.TopEnd),
                    onClick = onClickContinueReading,
                )
            }
            if (showTitle) {
                CoverTextOverlay(
                    title = title,
                )
            }
        },
    )
}

@Composable
fun ContinueReadingButton(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    IconButton(
        modifier = modifier
            .size(36.dp)
            .padding(6.dp)
            .border(BorderStroke(0.1.dp, Color(0xFFEDEDED)), CircleShape),
        onClick = onClick,
        shape = CircleShape,
        colors = IconButtonDefaults.iconButtonColors().copy(
            containerColor = Color(0xAD212121),
        ),
    ) {
        Icon(
            modifier = Modifier.padding(4.dp),
            imageVector = Icons.AutoMirrored.Default.MenuBook,
            contentDescription = stringResource(MR.strings.start_reading),
            tint = Color.White,
        )
    }
}

@Composable
private fun BoxScope.CoverTextOverlay(
    title: String,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color(0xAA000000),
                ),
            )
            .fillMaxHeight(0.33f)
            .fillMaxWidth()
            .align(Alignment.BottomCenter),
    )
    GridItemTitle(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .padding(8.dp),
        title = title,
        style = MaterialTheme.typography.titleSmall.copy(
            color = Color.White,
            shadow = Shadow(
                color = Color.Black,
                blurRadius = 4f,
            ),
        ),
        minLines = 1,
    )
}

@Composable
private fun GridItemTitle(
    title: String,
    style: TextStyle,
    minLines: Int,
    modifier: Modifier = Modifier,
    maxLines: Int = 2,
) {
    Text(
        modifier = modifier,
        text = title,
        fontSize = 12.sp,
        lineHeight = 18.sp,
        minLines = minLines,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        style = style,
    )
}

@Composable
fun MangaGridCover(
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    /**
     * Cover aspect ratio. Default 2:3 (book) for fixed-column grids; pass `null` in the
     * staggered grid path (when the user has turned `uniformGrid` off) to let each cover render
     * at the image's intrinsic ratio so adjacent items vary in height.
     */
    aspectRatio: Float? = MangaCoverRatio.BOOK,
    cover: @Composable BoxScope.() -> Unit = {},
    badgeSegments: List<BadgeSegment> = listOf(),
    content: @Composable (BoxScope.() -> Unit)? = null,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .then(if (aspectRatio != null) Modifier.aspectRatio(aspectRatio) else Modifier)
            .clip(RoundedCornerShape(12.dp))
            .then(if (border != null) Modifier.border(border, RoundedCornerShape(12.dp)) else Modifier),
    ) {
        cover()
        content?.invoke(this)
        Badge(
            segments = badgeSegments,
            modifier = Modifier.align(Alignment.TopStart),
            slant = 6.dp,
        )
    }
}

@Preview
@Composable
private fun MangaGridCoverPreview() {
    MaterialTheme {
        Scaffold { contentPadding ->
            LazyLibraryStaggeredGrid(
                columns = 3,
                contentPadding = contentPadding,
            ) {
                items(10) {
                    MangaComfortableGridItem(
                        coverData = MangaCoverModel(
                            mangaId = 0,
                            sourceId = 0,
                            url = "https://www.example.com/image.jpg",
                            lastModified = 0,
                            inLibrary = false,
                        ),
                        title = "dingus",
                        isSelected = true,
                        badgeSegments = listOf(
                            BadgeSegment.text(
                                backgroundColor = MaterialTheme.colorScheme.secondary,
                                text = "In Library",
                                textColor = MaterialTheme.colorScheme.onSecondary,
                            ),
                        ),
                        onClickContinueReading = {},
                    )
                }
            }
        }
    }
}
