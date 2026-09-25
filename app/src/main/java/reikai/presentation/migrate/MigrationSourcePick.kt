package reikai.presentation.migrate

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import eu.kanade.presentation.components.AppBar
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.automirroredrounded.ArrowForward
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource

/**
 * One merge-group member shown in the migrate-merge source picker. [coverData] is a Coil model (a
 * `NovelCover` for novels, the `Manga` itself for manga). [payload] is the domain entry behind the
 * row, which is what opening its details page needs.
 */
data class PickMember(
    val id: Long,
    val title: String,
    val coverData: Any?,
    val sourceName: String,
    val chapterCount: Int,
    val payload: Any,
)

/** The merge source picker: a selectable member list plus a Continue action. */
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
                val chapters =
                    pluralStringResource(MR.plurals.manga_num_chapters, member.chapterCount, member.chapterCount)
                MigrationPickRow(
                    title = member.title,
                    subtitle = "${member.sourceName} • $chapters",
                    coverData = member.coverData,
                    checked = member.id in checked,
                    onToggle = { onToggle(member.id) },
                    onClickCover = { onClickCover(member) },
                )
            }
        }
    }
}
