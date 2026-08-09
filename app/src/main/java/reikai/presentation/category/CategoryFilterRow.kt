package reikai.presentation.category

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.StringResource
import reikai.domain.category.categoriesForContentType
import reikai.domain.category.mergeCategorySelection
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
import tachiyomi.core.common.preference.TriState
import tachiyomi.domain.category.model.Category
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsItemsPaddings
import tachiyomi.presentation.core.i18n.stringResource

/**
 * One include/exclude picker section over [categories]. [onConfirm] receives the WHOLE selection to
 * store, not just this section's ids: the dialog merges the section's result over [included] /
 * [excluded] so a stored id whose category this section does not display survives the confirm. The row
 * owns the master on/off toggle.
 */
data class CategoryFilterSection(
    val headingRes: StringResource?,
    val categories: List<Category>,
    val included: Set<Long>,
    val excluded: Set<Long>,
    val onConfirm: (include: Set<Long>, exclude: Set<Long>) -> Unit,
)

/**
 * Shared include/exclude category-filter row + picker dialog, backing the library (manga + novel) and
 * Updates filter sheets so it lives in one place instead of being copy-pasted per surface. A single
 * [section][sections] renders without a heading; multiple sections each get one. [onManageCategories]
 * shows the "Edit categories" shortcut, null hides it. Nothing renders when there is nothing to pick.
 * [showContentTypeChip] adds an All / Manga / Novels chip narrowing what the dialog lists, off by
 * default because the library sheet is already opened per content type.
 */
@Composable
fun ColumnScope.CategoryFilterRow(
    enabled: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    sections: List<CategoryFilterSection>,
    onManageCategories: (() -> Unit)? = null,
    showContentTypeChip: Boolean = false,
) {
    if (sections.isEmpty()) return

    var showDialog by rememberSaveable { mutableStateOf(false) }

    if (showDialog) {
        CategoryFilterDialog(
            sections = sections,
            onManageCategories = onManageCategories,
            showContentTypeChip = showContentTypeChip,
            onDismiss = { showDialog = false },
        )
    }

    Row(
        modifier = Modifier
            .clickable { onToggleEnabled(!enabled) }
            .fillMaxWidth()
            .padding(horizontal = SettingsItemsPaddings.Horizontal),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Checkbox(checked = enabled, onCheckedChange = null)
        Text(text = stringResource(MR.strings.categories), style = MaterialTheme.typography.bodyMedium)
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = { showDialog = true }) {
            Text(stringResource(MR.strings.action_edit))
        }
    }
}

@Composable
private fun CategoryFilterDialog(
    sections: List<CategoryFilterSection>,
    onManageCategories: (() -> Unit)?,
    showContentTypeChip: Boolean,
    onDismiss: () -> Unit,
) {
    val defaultLabel = stringResource(MR.strings.label_default)
    // Key on the stable section contents (not the whole section: its onConfirm lambda is a fresh
    // instance each recomposition, which would otherwise reset in-dialog selections).
    val stateKey = sections.map { Triple(it.categories, it.included, it.excluded) }
    val states = remember(stateKey) {
        sections.map { categoryStatesOf(it.categories, it.included, it.excluded) }
    }
    // A single section needs no heading; only label the sections when more than one is shown.
    val showHeadings = sections.size > 1
    // The chip narrows what is drawn, never the state map or the confirm below: every category keeps
    // its state whichever chip is up, so switching chips cannot drop a pick or a stored id.
    var chipContentType by rememberSaveable { mutableStateOf(ContentType.ALL) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.categories)) },
        text = {
            Column {
                if (showContentTypeChip) {
                    ContentTypeFilterChips(
                        selected = chipContentType,
                        onSelect = { chipContentType = it },
                    )
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    sections.forEachIndexed { index, section ->
                        if (showHeadings && section.headingRes != null) HeadingItem(section.headingRes)
                        CategoryTriStateRows(
                            categories = categoriesForContentType(section.categories, chipContentType),
                            states = states[index],
                            defaultLabel = defaultLabel,
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                // "Edit categories" sits on the left of the action row, opposite Cancel / OK.
                if (onManageCategories != null) {
                    TextButton(onClick = {
                        onDismiss()
                        onManageCategories()
                    }) {
                        Text(stringResource(MR.strings.action_edit_categories))
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(MR.strings.action_cancel))
                }
                TextButton(
                    onClick = {
                        sections.forEachIndexed { index, section ->
                            val shown = section.categories.mapTo(HashSet()) { it.id }
                            section.onConfirm(
                                mergeCategorySelection(
                                    section.included,
                                    shown,
                                    states[index].idsWith(TriState.ENABLED_IS),
                                ),
                                mergeCategorySelection(
                                    section.excluded,
                                    shown,
                                    states[index].idsWith(TriState.ENABLED_NOT),
                                ),
                            )
                        }
                        onDismiss()
                    },
                ) {
                    Text(stringResource(MR.strings.action_ok))
                }
            }
        },
    )
}
