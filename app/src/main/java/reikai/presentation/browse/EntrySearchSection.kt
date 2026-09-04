package reikai.presentation.browse

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import tachiyomi.presentation.core.components.material.padding

/**
 * One per-source section in a global-search result list: a tappable header (source name + subtitle +
 * forward arrow) over a [content] slot that renders that source's results / loading / error. The
 * header is clickable only when [onClick] is non-null. Shared by the manga and novel global search so
 * the two can't drift (ported from Mihon's GlobalSearchResultItem shape).
 */
@Composable
fun EntrySearchSection(
    title: String,
    subtitle: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    /** Long-pressing the header, where a surface offers something on it. */
    onLongClick: (() -> Unit)? = null,
    /** Drawn beside the title, for a list holding both content types. */
    badge: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.extraSmall)
                .fillMaxWidth()
                .then(
                    when {
                        onClick == null -> Modifier
                        else -> Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    },
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium)
                    badge()
                }
                Text(text = subtitle)
            }
            if (onClick != null) {
                IconButton(onClick = onClick) {
                    Icon(imageVector = MaterialSymbols.AutoMirroredRounded.ArrowForward, contentDescription = null)
                }
            }
        }
        content()
    }
}
