package yokai.presentation.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import yokai.i18n.MR

/**
 * Header rendered above each category in the library list / grid. Mirrors the legacy
 * `LibraryHeaderItem`:
 *
 *  - Category name, plus a parenthesised count when [showItemCount] is on (the
 *    `categoryNumberOfItems` preference).
 *  - When [collapsible] is true (groupLibraryBy = BY_DEFAULT), the whole row is clickable
 *    and a chevron indicates state. Dynamic grouping (BY_SOURCE / BY_TAG / etc.) renders the
 *    header non-interactively to match the legacy view where only default categories collapse.
 *  - A thin divider underneath separates the header from its items.
 */
@Composable
fun LibraryCategoryHeader(
    name: String,
    itemCount: Int,
    showItemCount: Boolean,
    isCollapsed: Boolean,
    collapsible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rowModifier = modifier
        .fillMaxWidth()
        .then(if (collapsible) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(horizontal = 8.dp, vertical = 8.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (collapsible) {
            // Legacy positions the chevron on the start (left) edge of the header row, before
            // the title. The caret rotates to convey state; the contentDescription describes
            // the action a tap performs so TalkBack users know what activating the row does.
            val collapseLabel = stringResource(MR.strings.collapse_category)
            val expandLabel = stringResource(MR.strings.expand_category)
            Icon(
                imageVector = if (isCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                contentDescription = null,
                modifier = Modifier
                    .size(20.dp)
                    .semantics {
                        contentDescription = if (isCollapsed) expandLabel else collapseLabel
                    },
            )
        }
        Text(
            text = if (showItemCount) "$name ($itemCount)" else name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier
                .weight(1f)
                .padding(start = if (collapsible) 8.dp else 0.dp),
        )
    }
    HorizontalDivider(
        thickness = 0.5.dp,
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}
