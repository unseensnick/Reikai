package reikai.presentation.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import eu.kanade.presentation.components.DropdownMenu
import eu.kanade.presentation.manga.components.DotSeparatorText
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.tachiyomi.util.system.copyToClipboard
import exh.debug.LocalCoverImagesHidden
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Brush
import mihon.icons.materialsymbols.rounded.Person
import mihon.icons.materialsymbols.rounded.Warning
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelCover
import reikai.domain.novel.model.asNovelCover
import reikai.presentation.components.entryStatusIcon
import reikai.presentation.components.entryStatusRes
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.clickableNoIndication
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * Content-agnostic header data for the shared [EntryInfoBox]. [coverModel] is a coil model (a `Manga`
 * or a [NovelCover]), so each content type feeds its own object. [isStubSource] is false for content
 * types that cannot have one (novels).
 */
data class EntryHeaderUi(
    val coverModel: Any,
    val title: String,
    val author: String?,
    val artist: String?,
    val status: Long,
    val sourceName: String,
    val isStubSource: Boolean,
    /** The library query for the viewed source's entries; null where [sourceName] labels a merged group. */
    val sourceQuery: String?,
)

fun Manga.toEntryHeader(sourceName: String, isStubSource: Boolean, sourceQuery: String?) = EntryHeaderUi(
    coverModel = this,
    title = title,
    author = author,
    artist = artist,
    status = status,
    sourceName = sourceName,
    isStubSource = isStubSource,
    sourceQuery = sourceQuery,
)

fun Novel.toEntryHeader(sourceName: String, sourceQuery: String?) = EntryHeaderUi(
    coverModel = asNovelCover(),
    title = title,
    author = author,
    artist = artist,
    status = status,
    sourceName = sourceName,
    // stub sources are a manga-extension concept; novels never have one
    isStubSource = false,
    sourceQuery = sourceQuery,
)

/**
 * Shared details header (blurred cover backdrop + cover + title / author / artist / status / source)
 * for manga and novels. Replaces MangaInfoBox + NovelInfoBox. Status codes match between the two
 * (see NovelStatusCode), so the status icon + label render from one switch. Tapping the title / author
 * / artist runs [onGlobalSearch] and tapping the source browses it; long-press offers library search,
 * global search and copy (Browse, library search and copy on the source).
 */
@Composable
fun EntryInfoBox(
    isTabletUi: Boolean,
    appBarPadding: Dp,
    header: EntryHeaderUi,
    onCoverClick: () -> Unit,
    onGlobalSearch: (query: String) -> Unit,
    librarySearch: (query: String) -> Unit,
    onBrowseSource: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        val backdropGradientColors = listOf(
            Color.Transparent,
            MaterialTheme.colorScheme.background,
        )
        // The backdrop is the cover image too, so hiding covers from the debug menu drops it.
        if (!LocalCoverImagesHidden.current) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(header.coverModel)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .drawWithContent {
                        drawContent()
                        drawRect(brush = Brush.verticalGradient(colors = backdropGradientColors))
                    }
                    .blur(4.dp)
                    .alpha(0.2f),
            )
        }

        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            if (!isTabletUi) {
                EntryTitlesSmall(
                    appBarPadding = appBarPadding,
                    header = header,
                    onCoverClick = onCoverClick,
                    onGlobalSearch = onGlobalSearch,
                    librarySearch = librarySearch,
                    onBrowseSource = onBrowseSource,
                )
            } else {
                EntryTitlesLarge(
                    appBarPadding = appBarPadding,
                    header = header,
                    onCoverClick = onCoverClick,
                    onGlobalSearch = onGlobalSearch,
                    librarySearch = librarySearch,
                    onBrowseSource = onBrowseSource,
                )
            }
        }
    }
}

@Composable
private fun EntryTitlesLarge(
    appBarPadding: Dp,
    header: EntryHeaderUi,
    onCoverClick: () -> Unit,
    onGlobalSearch: (query: String) -> Unit,
    librarySearch: (query: String) -> Unit,
    onBrowseSource: (() -> Unit)?,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = appBarPadding + 16.dp, end = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        MangaCover.Book(
            modifier = Modifier.fillMaxWidth(0.65f),
            data = header.coverModel,
            contentDescription = stringResource(MR.strings.manga_cover),
            onClick = onCoverClick,
        )
        Spacer(modifier = Modifier.height(16.dp))
        EntryContentInfo(
            header = header,
            onGlobalSearch = onGlobalSearch,
            librarySearch = librarySearch,
            onBrowseSource = onBrowseSource,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun EntryTitlesSmall(
    appBarPadding: Dp,
    header: EntryHeaderUi,
    onCoverClick: () -> Unit,
    onGlobalSearch: (query: String) -> Unit,
    librarySearch: (query: String) -> Unit,
    onBrowseSource: (() -> Unit)?,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, top = appBarPadding + 16.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            modifier = Modifier
                .sizeIn(maxWidth = 100.dp)
                .align(Alignment.Top),
            data = header.coverModel,
            contentDescription = stringResource(MR.strings.manga_cover),
            onClick = onCoverClick,
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            EntryContentInfo(
                header = header,
                onGlobalSearch = onGlobalSearch,
                librarySearch = librarySearch,
                onBrowseSource = onBrowseSource,
            )
        }
    }
}

@Composable
private fun ColumnScope.EntryContentInfo(
    header: EntryHeaderUi,
    onGlobalSearch: (query: String) -> Unit,
    librarySearch: (query: String) -> Unit,
    onBrowseSource: (() -> Unit)?,
    textAlign: TextAlign? = LocalTextStyle.current.textAlign,
) {
    val context = LocalContext.current
    val title = header.title
    val author = header.author
    val artist = header.artist

    // One menu for all four rows, as the tag chips do it: it anchors to this column rather
    // than to the row under the finger, which is the cost of not building four of them.
    // Deliberately remember, not rememberSaveable: an open menu should not survive a rotation
    // and reopen against a stale anchor.
    var showMenu by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf("") }
    var menuIsSource by remember { mutableStateOf(false) }

    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
        // The source row searches the library by the source's key rather than its name, which two
        // sources can share, and offers no search where the name labels a merged group.
        if (menuIsSource) {
            if (onBrowseSource != null) {
                DropdownMenuItem(
                    text = { Text(text = stringResource(MR.strings.browse)) },
                    onClick = {
                        onBrowseSource()
                        showMenu = false
                    },
                )
            }
            header.sourceQuery?.let { query ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(MR.strings.action_library_search)) },
                    onClick = {
                        librarySearch(query)
                        showMenu = false
                    },
                )
            }
        } else {
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_library_search)) },
                onClick = {
                    librarySearch(menuTarget)
                    showMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text(text = stringResource(MR.strings.action_global_search)) },
                onClick = {
                    onGlobalSearch(menuTarget)
                    showMenu = false
                },
            )
        }
        DropdownMenuItem(
            text = { Text(text = stringResource(MR.strings.action_copy_to_clipboard)) },
            onClick = {
                context.copyToClipboard(menuTarget, menuTarget)
                showMenu = false
            },
        )
    }

    Text(
        text = title.ifBlank { stringResource(MR.strings.unknown_title) },
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.clickableNoIndication(
            onLongClick = {
                if (title.isNotBlank()) {
                    menuTarget = title
                    menuIsSource = false
                    showMenu = true
                }
            },
            onClick = { if (title.isNotBlank()) onGlobalSearch(title) },
        ),
        textAlign = textAlign,
    )

    Spacer(modifier = Modifier.height(2.dp))

    Row(
        modifier = Modifier.secondaryItemAlpha(),
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(imageVector = MaterialSymbols.Rounded.Person, contentDescription = null, modifier = Modifier.size(16.dp))
        Text(
            text = author?.takeIf { it.isNotBlank() } ?: stringResource(MR.strings.unknown_author),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.clickableNoIndication(
                onLongClick = {
                    if (!author.isNullOrBlank()) {
                        menuTarget = author
                        menuIsSource = false
                        showMenu = true
                    }
                },
                onClick = { if (!author.isNullOrBlank()) onGlobalSearch(author) },
            ),
            textAlign = textAlign,
        )
    }

    if (!artist.isNullOrBlank() && author != artist) {
        Row(
            modifier = Modifier.secondaryItemAlpha(),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Brush,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
            )
            Text(
                text = artist,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.clickableNoIndication(
                    onLongClick = {
                        menuTarget = artist
                        menuIsSource = false
                        showMenu = true
                    },
                    onClick = { onGlobalSearch(artist) },
                ),
                textAlign = textAlign,
            )
        }
    }

    Spacer(modifier = Modifier.height(2.dp))

    Row(
        modifier = Modifier.secondaryItemAlpha(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = entryStatusIcon(header.status),
            contentDescription = null,
            modifier = Modifier
                .padding(end = 4.dp)
                .size(16.dp),
        )
        ProvideTextStyle(MaterialTheme.typography.bodyMedium) {
            Text(
                text = stringResource(entryStatusRes(header.status)),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
            DotSeparatorText()
            if (header.isStubSource) {
                Icon(
                    imageVector = MaterialSymbols.Rounded.Warning,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(16.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
            Text(
                text = header.sourceName,
                modifier = Modifier.clickableNoIndication(
                    onLongClick = {
                        menuTarget = header.sourceName
                        menuIsSource = true
                        showMenu = true
                    },
                    onClick = { onBrowseSource?.invoke() },
                ),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
            )
        }
    }
}
