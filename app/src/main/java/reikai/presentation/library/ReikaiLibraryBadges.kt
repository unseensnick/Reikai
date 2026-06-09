package reikai.presentation.library

import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.LocalLibrary
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import eu.kanade.domain.source.model.icon
import tachiyomi.domain.source.model.Source
import tachiyomi.presentation.core.components.Badge
import tachiyomi.source.local.LocalSource

/**
 * Net-new Reikai cover badges, used by every library cell (single-list, panorama, and the pager's
 * grids). Kept in their own file so the badge logic stays out of Mihon's components.
 */

/**
 * Source/extension icon badge. Ported from Komikku's `SourceIconBadge`, re-typed onto Reikai's
 * domain [Source]. Resolves the icon via [Source.icon] (an ExtensionManager lookup); reading it in
 * the composable is the same Injekt-in-composable idiom Mihon/Komikku use for source icons.
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

@Composable
fun SourceIconBadge(source: Source?) {
    if (source == null) return
    val icon = source.icon
    when {
        source.isStub && icon == null -> Badge(
            imageVector = Icons.Filled.Warning,
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
            imageVector = Icons.Outlined.Folder,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
        else -> Badge(
            imageVector = Icons.Outlined.LocalLibrary,
            color = MaterialTheme.colorScheme.tertiary,
            iconColor = MaterialTheme.colorScheme.onTertiary,
        )
    }
}
