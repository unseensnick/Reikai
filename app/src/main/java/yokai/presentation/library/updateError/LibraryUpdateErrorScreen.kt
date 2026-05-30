package yokai.presentation.library.updateError

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.rememberScreenModel
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.isTablet
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import yokai.i18n.MR
import yokai.presentation.component.EmptyScreen
import yokai.presentation.component.ReikaiTopBar
import yokai.presentation.component.ReikaiTopBarDefaults
import yokai.presentation.details.manga.MangaDetailsComposeController
import yokai.presentation.library.components.SelectionAction
import yokai.presentation.library.components.SelectionAppBar
import yokai.presentation.manga.components.MangaListItem
import yokai.util.Screen

/**
 * Lists library manga whose last update failed, grouped by error message. Reached from the
 * Compose library top-bar overflow ("Update errors (N)"). Tapping a row opens the manga;
 * long-press multi-selects rows for dismissal; Retry re-runs the whole library update.
 */
class LibraryUpdateErrorScreen : Screen() {

    @Composable
    override fun Content() {
        val onBackPress = LocalBackPress.currentOrThrow
        val router = LocalRouter.currentOrThrow
        val context = LocalContext.current
        val haptic = LocalHapticFeedback.current

        val screenModel = rememberScreenModel { LibraryUpdateErrorScreenModel() }
        val state by screenModel.state.collectAsState()

        val loaded = state as? LibraryUpdateErrorScreenModel.State.Loaded
        val groups = loaded?.groups.orEmpty()
        val selection = loaded?.selected.orEmpty()
        val selectionActive = selection.isNotEmpty()

        Scaffold(
            topBar = {
                if (selectionActive) {
                    SelectionAppBar(
                        selectionCount = selection.size,
                        onClose = screenModel::clearSelection,
                        colors = ReikaiTopBarDefaults.colors(),
                        actions = listOf(
                            SelectionAction(
                                label = stringResource(MR.strings.remove),
                                icon = Icons.Outlined.Delete,
                                onClick = screenModel::dismissSelected,
                            ),
                            // No icon -> lands in the bar's MoreVert overflow as a text item.
                            SelectionAction(
                                label = stringResource(MR.strings.select_all),
                                onClick = screenModel::toggleSelectAll,
                            ),
                        ),
                    )
                } else {
                    ReikaiTopBar(
                        title = stringResource(MR.strings.update_errors),
                        navigationIcon = {
                            IconButton(onClick = onBackPress) {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Outlined.ArrowBack,
                                    contentDescription = stringResource(MR.strings.back),
                                )
                            }
                        },
                        actions = {
                            IconButton(
                                onClick = screenModel::dismissAll,
                                enabled = groups.isNotEmpty(),
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.DeleteSweep,
                                    contentDescription = stringResource(MR.strings.clear_all),
                                )
                            }
                            IconButton(
                                onClick = {
                                    screenModel.retryAll(context)
                                    context.toast(MR.strings.updating_library)
                                },
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.Refresh,
                                    contentDescription = stringResource(MR.strings.retry),
                                )
                            }
                        },
                    )
                }
            },
        ) { innerPadding ->
            if (loaded != null && loaded.isEmpty) {
                EmptyScreen(
                    modifier = Modifier.padding(innerPadding),
                    image = Icons.Outlined.ErrorOutline,
                    message = stringResource(MR.strings.no_update_errors),
                    isTablet = isTablet(),
                )
                return@Scaffold
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                groups.forEach { group ->
                    item(key = "header-${group.message}") {
                        Text(
                            text = group.message,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                    items(
                        items = group.items,
                        key = { it.errorId },
                    ) { errorItem ->
                        MangaListItem(
                            coverData = errorItem.cover,
                            title = errorItem.title,
                            subtitle = errorItem.sourceName,
                            isSelected = errorItem.errorId in selection,
                            onClick = {
                                if (selectionActive) {
                                    screenModel.toggleSelection(errorItem.errorId, fromLongPress = false)
                                } else {
                                    router.pushController(
                                        MangaDetailsComposeController(errorItem.mangaId).withFadeTransaction(),
                                    )
                                }
                            },
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                screenModel.toggleSelection(errorItem.errorId, fromLongPress = true)
                            },
                        )
                    }
                }
            }
        }
    }
}
