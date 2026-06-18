package reikai.presentation.library.novels

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import reikai.presentation.category.CategoryFilterRow
import reikai.presentation.category.CategoryFilterSection
import reikai.presentation.category.toLongIdSet
import tachiyomi.presentation.core.util.collectAsState as collectAsPrefState

/**
 * Novel-library include/exclude category filter row, the novel twin of the manga
 * [reikai.presentation.library.ReikaiCategoriesFilter]. Binds [NovelLibraryScreenModel]'s
 * novel-specific category-filter prefs to the shared [CategoryFilterRow]; [onManageCategories]
 * routes to the Novels category manager for full parity with manga.
 */
@Composable
fun ColumnScope.NovelCategoriesFilter(
    screenModel: NovelLibraryScreenModel,
    onManageCategories: () -> Unit,
) {
    val enabled by screenModel.filterCategoriesEnabled.collectAsPrefState()
    val included by screenModel.filterCategoriesInclude.collectAsPrefState()
    val excluded by screenModel.filterCategoriesExclude.collectAsPrefState()
    val categories by screenModel.filterPickerCategories.collectAsState()

    CategoryFilterRow(
        enabled = enabled,
        onToggleEnabled = screenModel::setFilterCategories,
        sections = listOf(
            CategoryFilterSection(
                headingRes = null,
                categories = categories,
                included = included.toLongIdSet(),
                excluded = excluded.toLongIdSet(),
                onConfirm = screenModel::setCategoryFilterSelections,
            ),
        ),
        onManageCategories = onManageCategories,
    )
}
