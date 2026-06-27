package reikai.presentation.migrate

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * One merge-group member shown in the migrate-merge source picker. [coverData] is a Coil model (a
 * `NovelCover` for novels, the `Manga` itself for manga); [subtitle] is the "source name . N ch" line.
 */
data class PickMember(
    val id: Long,
    val title: String,
    val coverData: Any?,
    val subtitle: String,
)

/**
 * Shared UI for the migrate-merge source picker (manga + novel): a checkable member list plus a
 * Continue action. The hosting `Screen` keeps the type-specific bits, resolving the group, skipping
 * when nothing's merged, and where Continue navigates.
 */
@Composable
fun MigrationSourcePickContent(
    members: List<PickMember>,
    checked: Set<Long>,
    onToggle: (Long) -> Unit,
    onContinue: () -> Unit,
    navigateUp: () -> Unit,
) {
    Scaffold(
        topBar = {
            AppBar(
                title = stringResource(MR.strings.action_migrate),
                navigateUp = navigateUp,
                scrollBehavior = it,
            )
        },
        floatingActionButton = {
            if (checked.isNotEmpty()) {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.migrationConfigScreen_continueButtonText)) },
                    icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                    onClick = onContinue,
                )
            }
        },
    ) { contentPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            items(items = members, key = { it.id }) { member ->
                MemberRow(member = member, checked = member.id in checked, onToggle = { onToggle(member.id) })
            }
        }
    }
}

@Composable
private fun MemberRow(member: PickMember, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        MangaCover.Book(data = member.coverData, modifier = Modifier.width(48.dp).padding(start = 4.dp))
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = member.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = member.subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
