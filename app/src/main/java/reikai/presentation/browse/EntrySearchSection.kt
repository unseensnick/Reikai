package reikai.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    content: @Composable () -> Unit,
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .padding(start = MaterialTheme.padding.medium, end = MaterialTheme.padding.extraSmall)
                .fillMaxWidth()
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(text = title, style = MaterialTheme.typography.titleMedium)
                Text(text = subtitle)
            }
            if (onClick != null) {
                IconButton(onClick = onClick) {
                    Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null)
                }
            }
        }
        content()
    }
}
