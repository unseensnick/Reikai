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
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.selectedBackground

/**
 * One merge-group member shown in the migrate-merge source picker. [coverData] is a Coil model (a
 * `NovelCover` for novels, the `Manga` itself for manga); [subtitle] is the "source name . N ch" line.
 * [payload] is the domain entry behind the row, which is what opening its details page needs.
 */
data class PickMember(
    val id: Long,
    val title: String,
    val coverData: Any?,
    val subtitle: String,
    val payload: Any,
)

/** The [PickMember.subtitle] line, so both content types read the same. */
fun memberSubtitle(sourceName: String, chapterCount: Int): String = "$sourceName  $chapterCount"

/**
 * Shared UI for the migrate-merge source picker (manga + novel): a selectable member list plus a
 * Continue action. The hosting `Screen` keeps the type-specific bits, resolving the group, skipping
 * when nothing's merged, and where Continue navigates.
 */
@Composable
fun MigrationSourcePickContent(
    members: List<PickMember>,
    checked: Set<Long>,
    onToggle: (Long) -> Unit,
    onClickCover: (PickMember) -> Unit,
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
                    icon = {
                        Icon(imageVector = MaterialSymbols.AutoMirroredRounded.ArrowForward, contentDescription = null)
                    },
                    onClick = onContinue,
                )
            }
        },
    ) { contentPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
            items(items = members, key = { it.id }) { member ->
                MemberRow(
                    member = member,
                    checked = member.id in checked,
                    onToggle = { onToggle(member.id) },
                    onClickCover = { onClickCover(member) },
                )
            }
        }
    }
}

private val COVER_WIDTH = 40.dp

/**
 * One merge-group member to pick. Selection shows as the row's background rather than a checkbox,
 * and the cover is its own tap target that opens the entry, both matching the favorites picker this
 * step leads into, so the two picking screens of one flow do not read as different apps.
 */
@Composable
private fun MemberRow(
    member: PickMember,
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
            data = member.coverData,
            modifier = Modifier.width(COVER_WIDTH),
            onClick = onClickCover,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = MaterialTheme.padding.medium),
        ) {
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
