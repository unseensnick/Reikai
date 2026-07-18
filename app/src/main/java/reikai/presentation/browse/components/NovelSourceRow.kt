package reikai.presentation.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import eu.kanade.presentation.browse.components.BaseBrowseItem
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.SECONDARY_ALPHA
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.theme.header
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * One row for a light-novel source, mirroring Mihon's [eu.kanade.presentation.browse.components.BaseSourceItem]
 * shape (icon, name + language, trailing action) but typed on the LN side (plain fields, not Mihon's
 * `Source`). Shared by the Sources-tab list and the Extensions-tab plugin manager.
 */
@Composable
fun NovelSourceRow(
    name: String,
    lang: String,
    iconUrl: String?,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onClickItem: () -> Unit = {},
    onLongClickItem: () -> Unit = {},
    action: @Composable RowScope.() -> Unit = {},
) {
    BaseBrowseItem(
        modifier = modifier,
        onClickItem = onClickItem,
        onLongClickItem = onLongClickItem,
        icon = { NovelSourceIcon(iconUrl) },
        action = action,
        content = {
            Column(
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.medium)
                    .weight(1f),
            ) {
                Text(
                    text = name,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val secondary = subtitle ?: lang.takeIf { it.isNotEmpty() }
                    ?.let { LocaleHelper.getSourceDisplayName(it, LocalContext.current) }
                if (secondary != null) {
                    Text(
                        modifier = Modifier.secondaryItemAlpha(),
                        text = secondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
    )
}

/**
 * Pin / unpin toggle for a light-novel source row, the novel twin of the manga sources list's pin
 * button. Filled pin (primary tint) when pinned, outlined (dim) when not.
 */
@Composable
fun NovelSourcePinButton(isPinned: Boolean, onClick: () -> Unit) {
    val icon = if (isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin
    val tint = if (isPinned) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onBackground.copy(alpha = SECONDARY_ALPHA)
    }
    val description = if (isPinned) MR.strings.action_unpin else MR.strings.action_pin
    IconButton(onClick = onClick) {
        Icon(imageVector = icon, tint = tint, contentDescription = stringResource(description))
    }
}

/**
 * Section header shared across the Reikai Browse surfaces (LN plugin manager sections, the unified
 * "All" view's Manga / Light novels dividers). Matches Mihon's extension/source header styling.
 */
@Composable
fun BrowseSectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.padding(horizontal = MaterialTheme.padding.medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
    ) {
        Text(
            text = title,
            modifier = Modifier
                .padding(vertical = 8.dp)
                .weight(1f),
            style = MaterialTheme.typography.header,
        )
        action()
    }
}

@Composable
private fun NovelSourceIcon(iconUrl: String?) {
    val modifier = Modifier
        .size(40.dp)
        .clip(RoundedCornerShape(4.dp))
    if (iconUrl.isNullOrEmpty()) {
        Icon(
            imageVector = Icons.AutoMirrored.Outlined.MenuBook,
            contentDescription = null,
            modifier = modifier.padding(4.dp),
        )
    } else {
        AsyncImage(
            model = iconUrl,
            contentDescription = null,
            modifier = modifier,
        )
    }
}
