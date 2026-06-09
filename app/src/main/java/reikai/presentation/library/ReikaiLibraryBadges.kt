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
