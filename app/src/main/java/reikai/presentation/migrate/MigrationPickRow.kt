package reikai.presentation.migrate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.manga.components.MangaCover
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.util.selectedBackground

/** Cover width of one picker row; its height follows the 2:3 book ratio. */
private val COVER_WIDTH = 40.dp

/**
 * One entry to pick in either migrate picker (the merge source pick and the favorites pick).
 * Selection shows as the row's background rather than a checkbox, and the cover is its own tap
 * target that opens the entry, so a title can be checked before it is picked.
 */
@Composable
fun MigrationPickRow(
    title: String,
    subtitle: String?,
    coverData: Any?,
    checked: Boolean,
    onToggle: () -> Unit,
    onClickCover: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectedBackground(checked)
            .clickable(onClick = onToggle)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover.Book(
            data = coverData,
            modifier = Modifier.width(COVER_WIDTH),
            onClick = onClickCover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
