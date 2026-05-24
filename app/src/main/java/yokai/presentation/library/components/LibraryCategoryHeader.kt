package yokai.presentation.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
    // Spacing mirrors library_category_header_item.xml:
    //   - Start space 6dp + chevron marginStart 8dp = 14dp before the chevron.
    //   - Title marginTop 28dp + paddingTop 4dp ≈ 32dp above the title baseline; bottom is
    //     marginBottom 6dp + paddingBottom 4dp ≈ 10dp. Translating that into the Row's
    //     vertical padding gives ~24.dp top / ~8.dp bottom, which matches the airier section
    //     break the legacy shows above each category in screenshots.
    //   - No divider under the header in legacy; the next item's own spacing is the visual
    //     break, not a hairline.
    val rowModifier = modifier
        .fillMaxWidth()
        .then(if (collapsible) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(start = 14.dp, end = 8.dp, top = 24.dp, bottom = 8.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (collapsible) {
            // Legacy positions the chevron on the start (left) edge of the header row, before
            // the title. The drawable is 14dp wide; matching the visual weight matters since
            // the larger Material default (20dp+) reads as a heavy button rather than a
            // subtle expand affordance.
            val collapseLabel = stringResource(MR.strings.collapse_category)
            val expandLabel = stringResource(MR.strings.expand_category)
            Icon(
                imageVector = if (isCollapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess,
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .semantics {
                        contentDescription = if (isCollapsed) expandLabel else collapseLabel
                    },
            )
        }
        Text(
            text = if (showItemCount) "$name ($itemCount)" else name,
            // Legacy library_category_header_item.xml uses ?textAppearanceTitleMedium with an
            // explicit android:textSize="18sp" override. MaterialTheme.typography.titleMedium
            // defaults to 16sp; copy with fontSize = 18.sp to match the legacy weight.
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp),
            modifier = Modifier
                .weight(1f)
                .padding(start = if (collapsible) 8.dp else 0.dp),
        )
    }
}
