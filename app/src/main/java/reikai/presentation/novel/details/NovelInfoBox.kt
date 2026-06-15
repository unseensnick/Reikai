package reikai.presentation.novel.details

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PersonOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.manga.components.MangaCover
import reikai.data.coil.NovelCover
import reikai.data.novel.NovelStatusCode
import reikai.domain.novel.model.Novel
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Novel details header, the novel twin of [eu.kanade.presentation.manga.components.MangaInfoBox]
 * (which is hard-typed to `Manga`). A blurred cover backdrop behind the cover + title / author /
 * status / source block. The cover loads by URL through a synthetic [MangaCoverData].
 */
@Composable
fun NovelInfoBox(
    isTabletUi: Boolean,
    appBarPadding: Dp,
    novel: Novel,
    sourceName: String,
    sourceSite: String?,
    onCoverClick: () -> Unit,
    onSearch: (String) -> Unit,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val coverData = remember(novel.thumbnailUrl, novel.coverLastModified, novel.favorite, sourceSite) {
        NovelCover(
            url = novel.thumbnailUrl,
            site = sourceSite,
            isNovelFavorite = novel.favorite,
            lastModified = novel.coverLastModified,
            novelId = novel.id,
        )
    }
    Box(modifier = modifier) {
        val backdropGradientColors = listOf(Color.Transparent, MaterialTheme.colorScheme.background)
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(coverData).crossfade(true).build(),
            contentDescription = null,
            modifier = Modifier
                .matchParentSize()
                .drawWithContent {
                    drawContent()
                    drawRect(brush = Brush.verticalGradient(colors = backdropGradientColors))
                }
                .blur(4.dp)
                .alpha(0.2f),
        )

        if (!isTabletUi) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = appBarPadding + 16.dp, end = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MangaCover.Book(
                    modifier = Modifier.sizeIn(maxWidth = 100.dp).align(Alignment.Top),
                    data = coverData,
                    onClick = onCoverClick,
                )
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    NovelContentInfo(novel = novel, sourceName = sourceName, onSearch = onSearch, onCopy = onCopy)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = appBarPadding + 16.dp, end = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                MangaCover.Book(
                    modifier = Modifier.fillMaxWidth(0.65f),
                    data = coverData,
                    onClick = onCoverClick,
                )
                Spacer(Modifier.height(16.dp))
                NovelContentInfo(novel = novel, sourceName = sourceName, onSearch = onSearch, onCopy = onCopy)
            }
        }
    }
}

@Composable
private fun ColumnScope.NovelContentInfo(
    novel: Novel,
    sourceName: String,
    onSearch: (String) -> Unit,
    onCopy: (String) -> Unit,
) {
    // Tap a metadata value to search it, long-press to copy it (mirrors MangaInfoBox).
    val title = novel.title.ifBlank { null }
    Text(
        text = title ?: stringResource(MR.strings.unknown_title),
        style = MaterialTheme.typography.titleLarge,
        modifier = if (title != null) {
            Modifier.combinedClickable(onClick = { onSearch(title) }, onLongClick = { onCopy(title) })
        } else {
            Modifier
        },
    )
    Spacer(Modifier.height(2.dp))
    val author = novel.author?.takeIf { it.isNotBlank() }
    InfoRow(icon = Icons.Filled.PersonOutline) {
        Text(
            text = author ?: stringResource(MR.strings.unknown_author),
            style = MaterialTheme.typography.titleSmall,
            modifier = if (author != null) {
                Modifier.combinedClickable(onClick = { onSearch(author) }, onLongClick = { onCopy(author) })
            } else {
                Modifier
            },
        )
    }
    InfoRow(
        modifier = Modifier.combinedClickable(onClick = { onSearch(sourceName) }, onLongClick = { onCopy(sourceName) }),
    ) {
        Text(
            text = "${novelStatusText(novel.status)} • $sourceName",
            style = MaterialTheme.typography.bodyMedium,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun InfoRow(
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(16.dp))
        content()
    }
}

@Composable
private fun novelStatusText(status: Long): String = when (status.toInt()) {
    NovelStatusCode.ONGOING -> stringResource(MR.strings.ongoing)
    NovelStatusCode.COMPLETED -> stringResource(MR.strings.completed)
    NovelStatusCode.LICENSED -> stringResource(MR.strings.licensed)
    NovelStatusCode.PUBLISHING_FINISHED -> stringResource(MR.strings.publishing_finished)
    NovelStatusCode.CANCELLED -> stringResource(MR.strings.cancelled)
    NovelStatusCode.ON_HIATUS -> stringResource(MR.strings.on_hiatus)
    else -> stringResource(MR.strings.unknown)
}

/**
 * Add-to-library / WebView / Share row. The novel twin of `MangaActionRow`, dropping the
 * tracking + fetch-interval buttons novels don't use.
 */
@Composable
fun NovelActionRow(
    favorite: Boolean,
    onAddToLibraryClicked: () -> Unit,
    onWebViewClicked: (() -> Unit)?,
    onShareClicked: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp)) {
        NovelActionButton(
            title = if (favorite) stringResource(MR.strings.in_library) else stringResource(MR.strings.add_to_library),
            icon = if (favorite) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder,
            color = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            onClick = onAddToLibraryClicked,
        )
        if (onWebViewClicked != null) {
            NovelActionButton(
                title = stringResource(MR.strings.action_web_view),
                icon = Icons.Outlined.Public,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onWebViewClicked,
            )
        }
        if (onShareClicked != null) {
            NovelActionButton(
                title = stringResource(MR.strings.action_share),
                icon = Icons.Outlined.Share,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                onClick = onShareClicked,
            )
        }
    }
}

@Composable
private fun RowScope.NovelActionButton(
    title: String,
    icon: ImageVector,
    color: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .weight(1f)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(imageVector = icon, contentDescription = title, tint = color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
