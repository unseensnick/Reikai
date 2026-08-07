package exh.pagepreview

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import exh.pagepreview.components.PagePreviewScreen

class PagePreviewScreen(private val mangaId: Long) : Screen() {

    @Composable
    override fun Content() {
        val viewModel = viewModel<PagePreviewViewModel>(
            factory = PagePreviewViewModel.Factory,
            extras = CreationExtras { set(PagePreviewViewModel.MANGA_ID_KEY, mangaId) },
        )
        val context = LocalContext.current
        val state by viewModel.state.collectAsState()
        val navigator = LocalNavigator.currentOrThrow
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
        context.startActivity(ReaderActivity.newIntent(context, state.manga.id, state.chapter.id, page))
    }
}
