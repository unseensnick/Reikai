package reikai.presentation.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.domain.source.model.icon
import eu.kanade.presentation.library.components.DownloadsBadge
import eu.kanade.presentation.library.components.LanguageBadge
import eu.kanade.presentation.library.components.UnreadBadge
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.library.LibraryItem
import exh.assets.EhAssets
import exh.assets.ehassets.EhLogo
import exh.source.NHENTAI_NET_SOURCE_ID
import exh.source.PURURIN_SOURCE_ID
import exh.source.eHentaiSourceIds
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Folder
import mihon.icons.materialsymbols.rounded.LocalLibrary
import mihon.icons.materialsymbols.rounded.Warning
import reikai.data.coil.NovelCover
import reikai.domain.entry.EntryId
import tachiyomi.domain.manga.model.MangaCover
import tachiyomi.domain.source.model.Source
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.source.local.LocalSource
import kotlin.math.roundToInt

/*
 * Net-new Reikai cover badges, used by every library cell (single-list, panorama, and the pager's
 * grids). Kept in their own file so the badge logic stays out of Mihon's components.
 */

private const val MAX_MERGE_ICONS = 3

/**
 * Badge for a collapsed merge group (more than one grouped source). When [sources] is populated
 * (the "show source icons on merged covers" setting is on) it shows up to three distinct source
 * icons plus a "+N" overflow; otherwise it falls back to the numeric group count.
 */
@Composable
fun MergeBadge(relatedMangaIds: List<Long>, sources: List<Source>) {
    val count = relatedMangaIds.size
    if (count <= 1) return
    if (sources.isEmpty()) {
        Badge(text = count.toString())
        return
    }
    val distinct = sources.distinctBy { it.id }
    val shown = distinct.take(MAX_MERGE_ICONS)
    shown.forEach { SourceIconBadge(it) }
    val extra = distinct.size - shown.size
    if (extra > 0) Badge(text = "+$extra")
}

/**
 * Merge badge for a collapsed NOVEL group, the coil-loaded twin of [MergeBadge]. When [iconUrls] is
 * populated (the "show source icons on merged covers" novel setting is on) it shows up to three source
 * icons plus a "+N" overflow; otherwise it falls back to the numeric group count.
 */
@Composable
fun NovelMergeBadge(relatedMangaIds: List<Long>, iconUrls: List<String>) {
    val count = relatedMangaIds.size
    if (count <= 1) return
    if (iconUrls.isEmpty()) {
        Badge(text = count.toString())
        return
    }
    val shown = iconUrls.take(MAX_MERGE_ICONS)
    shown.forEach { NovelSourceIconBadge(it) }
    val extra = iconUrls.size - shown.size
    if (extra > 0) Badge(text = "+$extra")
}

@Composable
fun SourceIconBadge(source: Source?) {
    if (source == null) return
    val icon = produceState<ImageBitmap?>(initialValue = null, source.id) { value = source.icon() }.value
    when {
        source.isStub && icon == null -> Badge(
            imageVector = MaterialSymbols.Rounded.Warning,
            color = MaterialTheme.colorScheme.errorContainer,
            iconColor = MaterialTheme.colorScheme.error,
        )
        icon != null -> Badge(
            imageBitmap = icon,
            modifier = Modifier
                .scale(1.3f)
                .height(18.dp),
        )
        source.id == LocalSource.ID -> Badge(
            imageVector = MaterialSymbols.Rounded.Folder,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
        // built-in E-Hentai / ExHentai ship no extension icon, so draw the EH mark on a white
        //     tile (same treatment as the browse SourceIcon) instead of the generic library glyph.
        source.id in eHentaiSourceIds -> EhSourceIconBadge()
        // built-in Pururin / nhentai.net likewise ship no extension icon; give each its logo so the
        //     library badge matches the browse source icon instead of the generic library glyph.
        source.id == PURURIN_SOURCE_ID -> PururinSourceIconBadge()
        source.id == NHENTAI_NET_SOURCE_ID -> NHentaiNetSourceIconBadge()
        else -> Badge(
            imageVector = MaterialSymbols.Rounded.LocalLibrary,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}

/** Source-icon badge for the built-in E-Hentai / ExHentai sources. The brand mark (its own dark red)
 *  is drawn untinted on a white tile so it reads on both themes, matching the browse [SourceIcon];
 *  scaled to the same footprint as the bitmap source badge. */
@Composable
private fun EhSourceIconBadge() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RectangleShape)
            .background(Color.White)
            .height(18.dp)
            .aspectRatio(1f),
    ) {
        Image(
            imageVector = EhAssets.EhLogo,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.8f),
        )
    }
}

/** Source-icon badge for the built-in Pururin source. Its logo sits on the same white tile as the
 *  E-Hentai mark so the two built-in adult sources read consistently. */
@Composable
private fun PururinSourceIconBadge() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RectangleShape)
            .background(Color.White)
            .height(18.dp)
            .aspectRatio(1f),
    ) {
        Image(
            painter = painterResource(R.drawable.pururin_logo),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(0.8f),
        )
    }
}

/** Source-icon badge for the built-in nhentai.net source. Its logo already carries a dark backdrop,
 *  so it's drawn edge-to-edge with no tile (matching the browse SourceIcon). */
@Composable
private fun NHentaiNetSourceIconBadge() {
    Image(
        painter = painterResource(R.drawable.nhentai_logo),
        contentDescription = null,
        modifier = Modifier
            .clip(RectangleShape)
            .height(18.dp)
            .aspectRatio(1f),
    )
}

/** Source-icon badge for a disguised novel: the source's icon is a CDN URL (novels carry no Mihon
 *  [Source] bitmap), so it's coil-loaded. Mirrors [SourceIconBadge]'s bitmap path exactly (same
 *  [Badge] geometry: a secondary-backed rectangle with the icon scaled to fill an 18dp square) so a
 *  novel cover's badge is visually identical to a manga's. Renders nothing when the URL is absent. */
@Composable
fun NovelSourceIconBadge(iconUrl: String?) {
    if (iconUrl.isNullOrEmpty()) return
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RectangleShape)
            .background(MaterialTheme.colorScheme.secondary),
    ) {
        AsyncImage(
            model = iconUrl,
            contentDescription = null,
            modifier = Modifier
                .scale(1.3f)
                .height(18.dp),
        )
    }
}

/**
 * Cover data for a library row: a [NovelCover] (carries the source site as a Referer, loaded through
 * the novel cover pipeline) for a novel, else the manga [MangaCover]. Returns [Any] because the shared
 * grid cells accept either model as coil data.
 */
fun libraryCoverModel(item: LibraryItem): Any {
    val manga = item.libraryManga.manga
    val entryId = item.entryId
    return if (entryId is EntryId.Novel) {
        NovelCover(
            url = manga.thumbnailUrl,
            site = item.badges.coverSite,
            isNovelFavorite = manga.favorite,
            lastModified = manga.coverLastModified,
            novelId = entryId.rawId,
        )
    } else {
        MangaCover(
            mangaId = manga.id,
            sourceId = manga.source,
            isMangaFavorite = manga.favorite,
            url = manga.thumbnailUrl,
            lastModified = manga.coverLastModified,
        )
    }
}

/**
 * How much horizontal room the cover's end badge group may occupy, in pixels, published by
 * [MangaGridCover] once the start group has been measured. Unbounded by default so the list layout
 * and the browse grids, which do not compete for a fixed cover width, are unaffected.
 */
val LocalCoverBadgeBudget = compositionLocalOf { Int.MAX_VALUE }

/** What survives in the end badge group at a given width. See [planEndBadges]. */
data class EndBadgePlan(
    val showLanguage: Boolean,
    val icons: Int,
    val overflow: Int,
    val showGroupCount: Boolean,
)

/**
 * Decides what the end badge group drops as the cover narrows. The cover width varies with the
 * columns setting (0..10), so the budget is measured rather than assumed: a fixed icon cap is either
 * too many at ten columns or needlessly stingy at two. Order of sacrifice is the owner's ruling:
 * icons fold into "+N", then into the plain group count, then the language badge goes. The caller
 * guarantees the unread count is never what loses.
 */
fun planEndBadges(
    budgetPx: Int,
    sourceCount: Int,
    maxIcons: Int,
    hasLanguage: Boolean,
    iconWidthPx: Int,
    textBadgeWidthPx: Int,
    canShowGroupCount: Boolean = true,
): EndBadgePlan {
    val nothing = EndBadgePlan(showLanguage = false, icons = 0, overflow = 0, showGroupCount = false)
    val languageCost = if (hasLanguage) textBadgeWidthPx else 0
    // Rungs 1-2: as many icons as fit, remainder folded into a "+N" badge.
    for (icons in minOf(sourceCount, maxIcons) downTo 1) {
        val overflow = sourceCount - icons
        val cost = languageCost + icons * iconWidthPx + if (overflow > 0) textBadgeWidthPx else 0
        if (cost <= budgetPx) {
            return EndBadgePlan(hasLanguage, icons, overflow, showGroupCount = false)
        }
    }
    // Rungs 3-4: no icon fits, so the group count stands in for them, and it outranks the language
    // badge. An unmerged row has no count to fall back to and skips straight to the last rung.
    if (canShowGroupCount) {
        if (languageCost + textBadgeWidthPx <= budgetPx) {
            return EndBadgePlan(hasLanguage, icons = 0, overflow = 0, showGroupCount = true)
        }
        if (textBadgeWidthPx <= budgetPx) {
            return nothing.copy(showGroupCount = true)
        }
    }
    // Rung 5: the language badge alone still beats an empty corner.
    if (hasLanguage && languageCost <= budgetPx) {
        return nothing.copy(showLanguage = true)
    }
    return nothing
}

/** Width estimates for the end group. Icons are fixed-size so this is exact for them; the text
 *  badges are sized generously, since the start group (whose width varies with the unread count's
 *  digits) is measured for real by the layout and is never the thing that gives way. */
private val IconBadgeWidth = 18.dp
private val TextBadgeWidth = 26.dp

/**
 * The end badge group for a library cell: the language badge plus the merge or source icons,
 * degraded together to fit [LocalCoverBadgeBudget]. Every cell uses it, grid, panorama and list,
 * so a merged entry degrades the same way whatever the display mode.
 */
@Composable
fun LibraryCoverEndBadges(item: LibraryItem) {
    val budget = LocalCoverBadgeBudget.current
    val density = LocalDensity.current
    val isNovel = item.entryId is EntryId.Novel
    val isMerged = item.relatedMangaIds.size > 1
    val mergedSources = item.badges.mergedSources.distinctBy { it.id }
    val hasOwnIcon = if (isNovel) !item.badges.sourceIconUrl.isNullOrEmpty() else item.badges.source != null
    // An unmerged row still spends the same budget: one source icon competing with the language badge.
    val sourceCount = if (isMerged) {
        if (isNovel) item.badges.mergedSourceIconUrls.size else mergedSources.size
    } else if (hasOwnIcon) {
        1
    } else {
        0
    }
    val hasLanguage = item.badges.isLocal || item.badges.sourceLanguage.isNotEmpty()

    val plan = remember(budget, sourceCount, hasLanguage, isMerged) {
        with(density) {
            planEndBadges(
                budgetPx = budget,
                sourceCount = sourceCount,
                maxIcons = if (isMerged) MAX_MERGE_ICONS else 1,
                hasLanguage = hasLanguage,
                iconWidthPx = IconBadgeWidth.roundToPx(),
                textBadgeWidthPx = TextBadgeWidth.roundToPx(),
                canShowGroupCount = isMerged,
            )
        }
    }

    if (plan.showLanguage) {
        LanguageBadge(isLocal = item.badges.isLocal, sourceLanguage = item.badges.sourceLanguage)
    }
    when {
        // The group count stands in for icons that no longer fit; an unmerged row has none to stand in for.
        plan.showGroupCount -> if (isMerged) Badge(text = item.relatedMangaIds.size.toString())
        plan.icons == 0 -> Unit
        !isMerged -> if (isNovel) {
            NovelSourceIconBadge(
                item.badges.sourceIconUrl,
            )
        } else {
            SourceIconBadge(item.badges.source)
        }
        isNovel -> {
            item.badges.mergedSourceIconUrls.take(plan.icons).forEach { NovelSourceIconBadge(it) }
            if (plan.overflow > 0) Badge(text = "+${plan.overflow}")
        }
        else -> {
            mergedSources.take(plan.icons).forEach { SourceIconBadge(it) }
            if (plan.overflow > 0) Badge(text = "+${plan.overflow}")
        }
    }
}

/**
 * Set by [MangaGridCover] when the start group cannot fit the cover even on its own. The download
 * count is the one it gives up: the unread count is the number the eye is scanning for, so it is the
 * last badge standing.
 */
val LocalCoverBadgeDropDownload = compositionLocalOf { false }

/** The cover's start badge group for a library grid cell. */
@Composable
fun LibraryCoverStartBadges(item: LibraryItem) {
    if (!LocalCoverBadgeDropDownload.current) {
        DownloadsBadge(count = item.badges.downloadCount)
    }
    UnreadBadge(count = item.badges.unreadCount)
}

/**
 * Lays the cover's two badge groups out against one shared width so the end group can never paint
 * over the start group, which is how a 408-unread cover used to render a confident "4". The start
 * group is measured first and keeps what it needs; the end group is handed the remainder through
 * [LocalCoverBadgeBudget] and degrades itself. Only when the start group alone overflows does it give
 * up its download badge, via [LocalCoverBadgeDropDownload].
 */
@Composable
fun CoverBadgeRow(
    modifier: Modifier,
    badgesStart: (@Composable RowScope.() -> Unit)?,
    badgesEnd: (@Composable RowScope.() -> Unit)?,
    spread: Boolean = true,
    widthFraction: Float = 1f,
) {
    SubcomposeLayout(modifier) { constraints ->
        val width = (constraints.maxWidth * widthFraction).roundToInt()

        // Each group is measured against a hard ceiling, so a badge the ladder's estimates did not
        // shrink enough is clipped at the cover's edge rather than spilling outside the cell.
        fun measure(slot: String, budget: Int, drop: Boolean, content: (@Composable RowScope.() -> Unit)?) =
            content?.let {
                subcompose(slot) {
                    CompositionLocalProvider(
                        LocalCoverBadgeBudget provides budget,
                        LocalCoverBadgeDropDownload provides drop,
                    ) { BadgeGroup(content = it) }
                }.map { measurable ->
                    measurable.measure(
                        Constraints(maxWidth = budget.coerceAtLeast(0), maxHeight = constraints.maxHeight),
                    )
                }
            }.orEmpty()

        var start = measure("start", width, drop = false, content = badgesStart)
        if (start.sumOf { it.width } > width) {
            start = measure("start-trimmed", width, drop = true, content = badgesStart)
        }
        val startWidth = start.sumOf { it.width }
        val end = measure("end", width - startWidth, drop = false, content = badgesEnd)
        val endWidth = end.sumOf { it.width }
        val height = (start + end).maxOfOrNull { it.height } ?: 0
        // The cover spreads its two groups to opposite corners; the list cell sizes to its content
        // and keeps them together, so the title beside it gets every pixel the badges did not need.
        val laidOutWidth = if (spread) width else startWidth + endWidth
        val endX = if (spread) (width - endWidth).coerceAtLeast(0) else startWidth

        layout(laidOutWidth, height) {
            start.fold(0) { x, p ->
                p.place(x, 0)
                x + p.width
            }
            end.fold(endX) { x, p ->
                p.place(x, 0)
                x + p.width
            }
        }
    }
}
