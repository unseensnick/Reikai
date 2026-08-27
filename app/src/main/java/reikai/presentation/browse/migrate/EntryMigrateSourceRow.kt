package reikai.presentation.browse.migrate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import eu.kanade.presentation.browse.components.BaseBrowseItem
import eu.kanade.tachiyomi.util.system.LocaleHelper
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Badge
import tachiyomi.presentation.core.components.BadgeGroup
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

/**
 * One migrate-from row, for either content type. Only the icon differs between them, so it is the
 * one slot the caller fills; the name, the language, the gone-source warning and the favourite count
 * are written once here.
 */
@Composable
fun EntryMigrateSourceRow(
    row: BrowseMigrateRow,
    onClickItem: () -> Unit,
    onLongClickItem: () -> Unit,
    modifier: Modifier = Modifier,
    /** Content-type badge, beside the name, drawn while the list holds both types. */
    badge: @Composable () -> Unit = {},
    icon: @Composable RowScope.() -> Unit,
) {
    BaseBrowseItem(
        modifier = modifier,
        onClickItem = onClickItem,
        onLongClickItem = onLongClickItem,
        icon = icon,
        action = { BadgeGroup { Badge(text = "${row.count}") } },
        content = {
            Column(
                modifier = Modifier
                    .padding(horizontal = MaterialTheme.padding.medium)
                    .weight(1f),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = row.name,
                        modifier = Modifier.weight(1f, fill = false),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    badge()
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (row.lang.isNotEmpty()) {
                        Text(
                            modifier = Modifier.secondaryItemAlpha(),
                            text = LocaleHelper.getSourceDisplayName(row.lang, LocalContext.current),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (row.isStub) {
                        Text(
                            modifier = Modifier.secondaryItemAlpha(),
                            text = stringResource(MR.strings.not_installed),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
    )
}
