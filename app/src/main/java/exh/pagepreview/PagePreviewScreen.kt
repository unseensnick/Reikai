package exh.pagepreview

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import exh.pagepreview.components.PagePreviewScreen
import kotlinx.serialization.Serializable
import mihon.core.navigation.LocalNavigator

@Serializable
data class PagePreviewRoute(val mangaId: Long) : NavKey

@Composable
fun PagePreviewScreen(mangaId: Long) {
    val viewModel = assistedMetroViewModel<PagePreviewViewModel, PagePreviewViewModel.Factory> {
        create(mangaId = mangaId)
    }
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val navigator = LocalNavigator.current
    PagePreviewScreen(
        state = state,
        pageDialogOpen = viewModel.pageDialogOpen,
        onPageSelected = viewModel::moveToPage,
        onOpenPage = { openPage(context, state, it) },
        onOpenPageDialog = { viewModel.pageDialogOpen = true },
        onDismissPageDialog = { viewModel.pageDialogOpen = false },
        navigateUp = navigator::pop,
    )
}

private fun openPage(context: Context, state: PagePreviewState, page: Int) {
    if (state !is PagePreviewState.Success) return
    context.openPreviewPage(state.chapter, page)
}
