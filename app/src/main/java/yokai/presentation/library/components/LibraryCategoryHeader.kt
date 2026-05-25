package yokai.presentation.library.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.R
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
 *  - When [onRefreshClick] is non-null, a trailing per-category refresh affordance renders
 *    after the title. Spinner replaces the icon while [isRefreshing] is true. Visibility is
 *    decided by the caller to match legacy `LibraryHeaderHolder.notifyStatus` (`isSingleCategory`
 *    + `showAllCategories` + dynamic-category gates).
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
    isRefreshing: Boolean = false,
    onRefreshClick: (() -> Unit)? = null,
    /**
     * When true, the header is in multi-select mode: the chevron is replaced with a
     * select-all-in-category circle, and tapping the row toggles every manga in the category
     * in/out of the selection set. Mirrors the legacy `LibraryHeaderHolder.notifyStatus`
     * MULTI-mode behavior at refs/yokai/.../LibraryHeaderHolder.kt:382-383.
     */
    selectionActive: Boolean = false,
    /** True when every manga in this category is currently selected. */
    allSelected: Boolean = false,
    /** Toggle every manga in this category in/out of the selection set. */
    onToggleCategorySelection: (() -> Unit)? = null,
) {
    // Spacing mirrors library_category_header_item.xml:
    //   - Start space 6dp + chevron marginStart 8dp = 14dp before the chevron.
    //   - Title marginTop 28dp + paddingTop 4dp ≈ 32dp above the title baseline; bottom is
    //     marginBottom 6dp + paddingBottom 4dp ≈ 10dp. Translating that into the Row's
    //     vertical padding gives ~24.dp top / ~8.dp bottom, which matches the airier section
    //     break the legacy shows above each category in screenshots.
    //   - No divider under the header in legacy; the next item's own spacing is the visual
    //     break, not a hairline.
    // In selection mode the row's tap toggles the whole-category selection (matches legacy
    // checkbox click handler at refs/yokai/.../LibraryHeaderHolder.kt:354-356); collapse is
    // unreachable during selection mode. Outside selection mode, behavior is unchanged.
    val rowClick = when {
        selectionActive && onToggleCategorySelection != null -> onToggleCategorySelection
        collapsible -> onClick
        else -> null
    }
    val rowModifier = modifier
        .fillMaxWidth()
        .then(if (rowClick != null) Modifier.clickable(onClick = rowClick) else Modifier)
        .padding(start = 14.dp, end = 8.dp, top = 24.dp, bottom = 8.dp)
    Row(
        modifier = rowModifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionActive) {
            // Select-all-in-category circle: filled CheckCircle when every manga in this
            // category is in the selection set, hollow RadioButtonUnchecked otherwise. Same
            // drawable swap as legacy at refs/yokai/.../LibraryHeaderHolder.kt:358-373.
            Icon(
                imageVector = if (allSelected) Icons.Outlined.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
                contentDescription = stringResource(MR.strings.select_all),
                tint = if (allSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        } else if (collapsible) {
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
                .padding(start = if (selectionActive || collapsible) 8.dp else 0.dp),
        )
        if (onRefreshClick != null) {
            HeaderRefreshButton(
                isRefreshing = isRefreshing,
                onClick = onRefreshClick,
            )
        }
    }
}

/**
 * Trailing refresh affordance. Raw [Box] instead of M3 `IconButton` to match legacy's exact
 * 42dp × 32dp footprint (library_category_header_item.xml). `IconButton`'s 48dp minimum and
 * rounded-rectangle defaults would fight the inner padding asymmetry. Same Phase-3 lesson
 * as `ContinueReadingButton`.
 *
 * While refreshing, the click is disabled (matches legacy `setRefreshing(true)`'s
 * `isClickable = false` at LibraryHeaderHolder.kt:248-262) and the icon swaps for a 20dp
 * progress indicator coloured `colorScheme.secondary` to match legacy
 * `runningDrawable.setColorSchemeColors(R.attr.colorSecondary)`.
 */
@Composable
private fun HeaderRefreshButton(
    isRefreshing: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 32.dp)
            .clip(CircleShape)
            .clickable(enabled = !isRefreshing, onClick = onClick)
            .padding(PaddingValues(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.secondary,
            )
        } else {
            Icon(
                painter = painterResource(R.drawable.ic_refresh_24dp),
                contentDescription = stringResource(MR.strings.update),
                tint = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
