package yokai.presentation.category.novel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.data.database.models.NovelCategory
import eu.kanade.tachiyomi.util.system.launchIO
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.novel.NovelCategoryRepository
import yokai.domain.novel.interactor.GetNovelCategories
import yokai.domain.novel.interactor.ReorderNovelCategories
import yokai.domain.novel.models.NovelCategoryUpdate
import yokai.i18n.MR
import yokai.presentation.AppBarType
import yokai.presentation.settings.SettingsScaffold
import yokai.util.Screen

/**
 * Compose-native categories management for the novel library. Reachable from the Novels-tab
 * Display sheet's "Add or edit categories" button (D4) when the user is on the Novels tab.
 *
 * Surface mirrors the manga [eu.kanade.tachiyomi.ui.category.CategoryController]:
 *   - List rows show the category name with reorder (up/down), rename, and delete actions.
 *   - Top-bar "+" opens an Add dialog.
 *   - Delete prompts a confirm dialog that warns entries reassign to the Default category
 *     (the same behaviour `NovelRepositoryImpl.setCategories` produces — a novel that lands
 *     in no user category falls into the synthetic Default).
 *
 * Compose-native rather than a port of the legacy `CategoryController` because the data layer
 * (`NovelCategoryRepository`, `GetNovelCategories`, `ReorderNovelCategories`) is already
 * complete; a Conductor port would re-implement ~900 lines of FastAdapter / Holder / Item
 * plumbing for a screen this compose tree handles in roughly a quarter the lines.
 */
class NovelCategoriesScreen : Screen() {

    @Composable
    override fun Content() {
        val repo = remember { Injekt.get<NovelCategoryRepository>() }
        val getCategories = remember { Injekt.get<GetNovelCategories>() }
        val reorder = remember { Injekt.get<ReorderNovelCategories>() }
        val scope = rememberCoroutineScope()
        val listState = rememberLazyListState()

        val categories by remember { getCategories.subscribe() }.collectAsState(initial = emptyList())

        var dialogState by remember { mutableStateOf<DialogState>(DialogState.None) }
        var errorMessage by remember { mutableStateOf<String?>(null) }
        // Pre-compute error strings here because stringResource is @Composable and the dialog
        // onConfirm callbacks are not composable contexts.
        val blankError = stringResource(MR.strings.category_cannot_be_blank)
        val existsError = stringResource(MR.strings.category_with_name_exists)

        SettingsScaffold(
            title = stringResource(MR.strings.edit_categories),
            appBarType = AppBarType.SMALL,
            appBarActions = {
                IconButton(onClick = { dialogState = DialogState.Add }) {
                    Icon(
                        imageVector = Icons.Outlined.Add,
                        contentDescription = stringResource(MR.strings.create_new_category),
                    )
                }
            },
        ) { padding ->
            CategoriesBody(
                categories = categories,
                listState = listState,
                padding = padding,
                onRename = { dialogState = DialogState.Rename(it) },
                onDelete = { dialogState = DialogState.Delete(it) },
                onMoveUp = { category ->
                    val index = categories.indexOf(category)
                    if (index <= 0) return@CategoriesBody
                    scope.launchIO {
                        reorder.await(
                            listOf(
                                NovelCategoryUpdate(
                                    id = category.id!!.toLong(),
                                    order = (index - 1).toLong(),
                                ),
                                NovelCategoryUpdate(
                                    id = categories[index - 1].id!!.toLong(),
                                    order = index.toLong(),
                                ),
                            ),
                        )
                    }
                },
                onMoveDown = { category ->
                    val index = categories.indexOf(category)
                    if (index < 0 || index >= categories.lastIndex) return@CategoriesBody
                    scope.launchIO {
                        reorder.await(
                            listOf(
                                NovelCategoryUpdate(
                                    id = category.id!!.toLong(),
                                    order = (index + 1).toLong(),
                                ),
                                NovelCategoryUpdate(
                                    id = categories[index + 1].id!!.toLong(),
                                    order = index.toLong(),
                                ),
                            ),
                        )
                    }
                },
            )
        }

        // The same dialog widget handles Add and Rename — only the title and the initial text
        // differ. Both write the name through the repository (insert for Add, update for
        // Rename) and validate against blank input + an existing name collision.
        when (val state = dialogState) {
            is DialogState.Add -> CategoryNameDialog(
                title = stringResource(MR.strings.create_new_category),
                initialName = "",
                error = errorMessage,
                onDismiss = {
                    dialogState = DialogState.None
                    errorMessage = null
                },
                onConfirm = { name ->
                    when {
                        name.isBlank() -> errorMessage = blankError
                        categories.any { it.name.equals(name, ignoreCase = true) } -> errorMessage = existsError
                        else -> {
                            errorMessage = null
                            scope.launchIO {
                                val category = NovelCategory.create(name).apply {
                                    order = categories.size
                                }
                                repo.insert(category)
                                dialogState = DialogState.None
                            }
                        }
                    }
                },
            )
            is DialogState.Rename -> CategoryNameDialog(
                title = stringResource(MR.strings.edit_categories),
                initialName = state.category.name,
                error = errorMessage,
                onDismiss = {
                    dialogState = DialogState.None
                    errorMessage = null
                },
                onConfirm = { name ->
                    when {
                        name.isBlank() -> errorMessage = blankError
                        name.equals(state.category.name, ignoreCase = false) -> {
                            // No-op rename; just close.
                            errorMessage = null
                            dialogState = DialogState.None
                        }
                        categories.any { it.name.equals(name, ignoreCase = true) } -> errorMessage = existsError
                        else -> {
                            errorMessage = null
                            scope.launchIO {
                                repo.update(
                                    NovelCategoryUpdate(
                                        id = state.category.id!!.toLong(),
                                        name = name,
                                    ),
                                )
                                dialogState = DialogState.None
                            }
                        }
                    }
                },
            )
            is DialogState.Delete -> DeleteCategoryDialog(
                category = state.category,
                onDismiss = { dialogState = DialogState.None },
                onConfirm = {
                    scope.launchIO {
                        repo.delete(state.category.id!!.toLong())
                        dialogState = DialogState.None
                    }
                },
            )
            DialogState.None -> Unit
        }

        // Cheap touch: when categories list grows (a new add), scroll to the bottom so the new
        // entry is visible. Conservative key (size only) avoids re-scrolling on every emission.
        LaunchedEffect(categories.size) {
            if (categories.isNotEmpty()) {
                listState.animateScrollToItem(categories.lastIndex)
            }
        }
    }

    private sealed interface DialogState {
        data object None : DialogState
        data object Add : DialogState
        data class Rename(val category: NovelCategory) : DialogState
        data class Delete(val category: NovelCategory) : DialogState
    }
}

@Composable
private fun CategoriesBody(
    categories: List<NovelCategory>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    padding: PaddingValues,
    onRename: (NovelCategory) -> Unit,
    onDelete: (NovelCategory) -> Unit,
    onMoveUp: (NovelCategory) -> Unit,
    onMoveDown: (NovelCategory) -> Unit,
) {
    if (categories.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(MR.strings.long_press_category),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        contentPadding = padding,
    ) {
        items(items = categories, key = { it.id ?: it.name.hashCode() }) { category ->
            val index = categories.indexOf(category)
            CategoryRow(
                category = category,
                canMoveUp = index > 0,
                canMoveDown = index < categories.lastIndex,
                onRename = { onRename(category) },
                onDelete = { onDelete(category) },
                onMoveUp = { onMoveUp(category) },
                onMoveDown = { onMoveDown(category) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
private fun CategoryRow(
    category: NovelCategory,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = category.name,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
        )
        IconButton(onClick = onMoveUp, enabled = canMoveUp) {
            Icon(
                imageVector = Icons.Outlined.ArrowUpward,
                contentDescription = "Move up",
            )
        }
        IconButton(onClick = onMoveDown, enabled = canMoveDown) {
            Icon(
                imageVector = Icons.Outlined.ArrowDownward,
                contentDescription = "Move down",
            )
        }
        IconButton(onClick = onRename) {
            Icon(
                imageVector = Icons.Outlined.Edit,
                contentDescription = stringResource(MR.strings.edit),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = stringResource(MR.strings.delete),
            )
        }
    }
}

@Composable
private fun CategoryNameDialog(
    title: String,
    initialName: String,
    error: String?,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    isError = error != null,
                    label = { Text(stringResource(MR.strings.new_category)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim()) }) {
                Text(stringResource(MR.strings.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.cancel))
            }
        },
    )
}

@Composable
private fun DeleteCategoryDialog(
    category: NovelCategory,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.confirm_category_deletion)) },
        text = {
            Column {
                Text(text = category.name, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = stringResource(MR.strings.confirm_category_deletion_message),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(MR.strings.delete))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(MR.strings.cancel))
            }
        },
    )
}
