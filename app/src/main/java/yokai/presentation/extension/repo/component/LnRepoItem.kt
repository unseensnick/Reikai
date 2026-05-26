package yokai.presentation.extension.repo.component

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Light-novel parallel of [ExtensionRepoItem] for the Phase 8 follow-up tabbed
 * `ExtensionRepoScreen`. Shows just the repo URL + a delete affordance — LN repos carry no
 * metadata equivalent to manga's signing-key / website / name, so the visual is intentionally
 * leaner than the manga row.
 */
@Composable
fun LnRepoItem(
    repoUrl: String,
    modifier: Modifier = Modifier,
    onDeleteClick: (String) -> Unit = {},
) {
    Row(
        modifier = modifier.padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            modifier = Modifier.padding(horizontal = 8.dp),
            imageVector = Icons.AutoMirrored.Outlined.Label,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            modifier = Modifier
                .weight(1.0f)
                .fillMaxWidth()
                .basicMarquee(),
            text = repoUrl,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 16.sp,
        )
        IconButton(onClick = { onDeleteClick(repoUrl) }) {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onBackground,
            )
        }
    }
}
