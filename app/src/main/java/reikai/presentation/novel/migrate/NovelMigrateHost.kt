package reikai.presentation.novel.migrate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import reikai.domain.novel.model.Novel
import reikai.presentation.novel.details.NovelScreen

/**
 * Reusable migrate-from-duplicate wiring, so every duplicate surface (details, browse, global search)
 * shares one migrate host instead of repeating the dialog state and render. A surface holds a controller
 * (via [rememberNovelMigrateController]), points the duplicate dialog's onMigrate at
 * [NovelMigrateController.start] with the tapped duplicate and the novel being added, and renders
 * [NovelMigrateHost]. The one-tap [MigrateNovelDialog] then carries the duplicate's state onto the target.
 */
@Stable
class NovelMigrateController {
    /** (current duplicate, target being added); non-null while the migrate dialog is open. */
    var request by mutableStateOf<Pair<Novel, Novel>?>(null)
        private set

    fun start(current: Novel, target: Novel) {
        request = current to target
    }

    fun dismiss() {
        request = null
    }
}

@Composable
fun rememberNovelMigrateController(): NovelMigrateController = remember { NovelMigrateController() }

@Composable
fun Screen.NovelMigrateHost(controller: NovelMigrateController) {
    val navigator = LocalNavigator.currentOrThrow
    val (current, target) = controller.request ?: return
    MigrateNovelDialog(
        current = current,
        target = target,
        onClickTitle = { navigator.push(NovelScreen(current.source, current.url)) },
        onDismissRequest = controller::dismiss,
    )
}
