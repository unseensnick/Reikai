package eu.kanade.tachiyomi.ui.category

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.util.fastMap
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.category.CategoryScreen
import eu.kanade.presentation.category.components.CategoryCreateDialog
import eu.kanade.presentation.category.components.CategoryDeleteDialog
import eu.kanade.presentation.category.components.CategoryRenameDialog
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import reikai.domain.library.ContentType
import reikai.presentation.components.ContentTypeFilterChips
import reikai.presentation.library.novels.NovelCategoryScreenModel
import tachiyomi.domain.category.model.Category
import tachiyomi.presentation.core.screens.LoadingScreen

// RK: `novels` opens straight on the Novels tab (the novel library's Edit-categories passes true).
class CategoryScreen(private val novels: Boolean = false) : Screen() {

    @Composable
    override fun Content() {
        var showNovels by rememberSaveable { mutableStateOf(novels) }
        // RK: shared Manga/Novels chip (same control as Browse/Library), rendered above the list.
        val header: @Composable () -> Unit = {
            ContentTypeFilterChips(
                selected = if (showNovels) ContentType.NOVELS else ContentType.MANGA,
                onSelect = { showNovels = it == ContentType.NOVELS },
                types = listOf(ContentType.MANGA, ContentType.NOVELS),
            )
        }
        if (showNovels) NovelCategoryContent(header) else MangaCategoryContent(header)
    }

    @Composable
    private fun MangaCategoryContent(header: @Composable () -> Unit) {
        val screenModel = rememberScreenModel { CategoryScreenModel() }
        val state by screenModel.state.collectAsState()
        CategoryManager(
            state = state,
            events = screenModel.events,
            header = header,
            onClickCreate = { screenModel.showDialog(CategoryDialog.Create) },
            onClickRename = { screenModel.showDialog(CategoryDialog.Rename(it)) },
            onClickDelete = { screenModel.showDialog(CategoryDialog.Delete(it)) },
            onToggleHidden = screenModel::toggleHidden,
            onChangeOrder = screenModel::changeOrder,
            onDismissDialog = screenModel::dismissDialog,
            onCreate = screenModel::createCategory,
            onRename = screenModel::renameCategory,
            onDelete = screenModel::deleteCategory,
        )
    }

    @Composable
    private fun NovelCategoryContent(header: @Composable () -> Unit) {
        val screenModel = rememberScreenModel { NovelCategoryScreenModel() }
        val state by screenModel.state.collectAsState()
        CategoryManager(
            state = state,
            events = screenModel.events,
            header = header,
            onClickCreate = { screenModel.showDialog(CategoryDialog.Create) },
            onClickRename = { screenModel.showDialog(CategoryDialog.Rename(it)) },
            onClickDelete = { screenModel.showDialog(CategoryDialog.Delete(it)) },
            onToggleHidden = screenModel::toggleHidden,
            onChangeOrder = screenModel::changeOrder,
            onDismissDialog = screenModel::dismissDialog,
            onCreate = screenModel::createCategory,
            onRename = screenModel::renameCategory,
            onDelete = screenModel::deleteCategory,
        )
    }
}

@Composable
private fun CategoryManager(
    state: CategoryScreenState,
    events: Flow<CategoryEvent>,
    header: @Composable () -> Unit,
    onClickCreate: () -> Unit,
    onClickRename: (Category) -> Unit,
    onClickDelete: (Category) -> Unit,
    onToggleHidden: (Category) -> Unit,
    onChangeOrder: (Category, Int) -> Unit,
    onDismissDialog: () -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Category, String) -> Unit,
    onDelete: (Long) -> Unit,
) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow

    if (state is CategoryScreenState.Loading) {
        LoadingScreen()
        return
    }
    val successState = state as CategoryScreenState.Success

    CategoryScreen(
        state = successState,
        onClickCreate = onClickCreate,
        onClickRename = onClickRename,
        onClickDelete = onClickDelete,
        onClickToggleHidden = onToggleHidden,
        onChangeOrder = onChangeOrder,
        navigateUp = navigator::pop,
        header = header,
    )

    when (val dialog = successState.dialog) {
        null -> {}
        CategoryDialog.Create -> CategoryCreateDialog(
            onDismissRequest = onDismissDialog,
            onCreate = onCreate,
            categories = successState.categories.fastMap { it.name },
        )
        is CategoryDialog.Rename -> CategoryRenameDialog(
            onDismissRequest = onDismissDialog,
            onRename = { onRename(dialog.category, it) },
            categories = successState.categories.fastMap { it.name },
            category = dialog.category.name,
        )
        is CategoryDialog.Delete -> CategoryDeleteDialog(
            onDismissRequest = onDismissDialog,
            onDelete = { onDelete(dialog.category.id) },
            category = dialog.category.name,
        )
    }

    LaunchedEffect(Unit) {
        events.collectLatest { event ->
            if (event is CategoryEvent.LocalizedMessage) {
                context.toast(event.stringRes)
            }
        }
    }
}
