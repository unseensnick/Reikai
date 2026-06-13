package reikai.presentation.novel.details

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Sort
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarActions
import eu.kanade.presentation.manga.components.ChapterHeader
import eu.kanade.presentation.manga.components.ExpandableMangaDescription
import eu.kanade.presentation.manga.components.MangaBottomActionMenu
import eu.kanade.presentation.manga.components.MangaChapterListItem
import eu.kanade.presentation.theme.TachiyomiTheme
import eu.kanade.presentation.util.Screen
import eu.kanade.presentation.util.isTabletUi
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelChapter
import tachiyomi.domain.library.service.LibraryPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.TwoPanelBox
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import java.text.DateFormat
import java.util.Date

/**
 * Light-novel details screen. The novel twin of `MangaScreen` (single-source for now): a header,
 * action row, expandable description, an (empty) merge slot, and the chapter list. Pushed from the
 * browse / global-search result tap, keyed by source + url (serializable). Reader, downloads, and
 * merge are stubbed (S4 / S5 / S8).
 */
class NovelScreen(
    private val sourceId: String,
    private val novelUrl: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val screenModel = rememberScreenModel { NovelDetailsScreenModel(sourceId, novelUrl) }
        val state by screenModel.state.collectAsState()

        when (val s = state) {
            NovelDetailsState.Loading -> LoadingScreen()
            is NovelDetailsState.Failed -> Scaffold(
                topBar = { AppBar(title = null, navigateUp = navigator::pop, scrollBehavior = it) },
            ) { padding -> EmptyScreen(message = s.message, modifier = Modifier.padding(padding)) }
            is NovelDetailsState.Loaded -> TachiyomiTheme(seedColor = s.seedColor) {
                NovelDetailsScaffold(
                    state = s,
                    screenModel = screenModel,
                    onBack = navigator::pop,
                    onWebView = {
                        s.sourceUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            navigator.push(WebViewScreen(url = url, initialTitle = s.sourceName, sourceId = null))
                        }
                    },
                    onShare = {
                        s.sourceUrl?.takeIf { it.isNotBlank() }?.let { url ->
                            context.startActivity(
                                Intent.createChooser(
                                    Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(Intent.EXTRA_TEXT, url)
                                    },
                                    null,
                                ),
                            )
                        }
                    },
                    onChapterClick = { /* TODO(S4): open the novel reader */ },
                )

                when (val dialog = s.dialog) {
                    is NovelDetailsDialog.ChangeCategory -> NovelCategoryDialog(
                        dialog = dialog,
                        onDismiss = screenModel::dismissDialog,
                        onConfirm = screenModel::applyCategories,
                    )
                    is NovelDetailsDialog.EditInfo -> EditNovelInfoDialog(
                        dialog = dialog,
                        onDismiss = screenModel::dismissDialog,
                        onReset = screenModel::resetNovelInfo,
                        onConfirm = screenModel::updateNovelInfo,
                    )
                    NovelDetailsDialog.ChapterSettings -> NovelChapterSettingsDialog(
                        sorting = s.sorting,
                        sortDescending = s.sortDescending,
                        readFilter = s.readFilter,
                        bookmarkedFilter = s.bookmarkedFilter,
                        hideChapterTitles = s.hideChapterTitles,
                        onDismiss = screenModel::dismissDialog,
                        onSortChange = screenModel::setSortOrder,
                        onFilterChange = screenModel::setFilters,
                        onDisplayChange = screenModel::setHideChapterTitles,
                        onSetSortDefault = screenModel::setGlobalSort,
                        onSetFilterDefault = screenModel::setGlobalFilters,
                    )
                    null -> {}
                }
            }
        }
    }
}

@Composable
private fun NovelDetailsScaffold(
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
    onBack: () -> Unit,
    onWebView: () -> Unit,
    onShare: () -> Unit,
    onChapterClick: (NovelChapter) -> Unit,
) {
    val selectionMode = state.selectionMode
    Scaffold(
        topBar = { scrollBehavior ->
            AppBar(
                title = state.novel.title,
                navigateUp = onBack,
                actionModeCounter = state.selection.size,
                onCancelActionMode = screenModel::clearSelection,
                actions = {
                    AppBarActions(
                        actions = listOf(
                            AppBar.Action(
                                title = stringResource(MR.strings.action_sort),
                                icon = Icons.AutoMirrored.Outlined.Sort,
                                onClick = screenModel::showChapterSettingsDialog,
                            ),
                            AppBar.OverflowAction(title = stringResource(MR.strings.action_webview_refresh), onClick = screenModel::refresh),
                            AppBar.OverflowAction(title = stringResource(MR.strings.action_edit), onClick = screenModel::showEditNovelInfoDialog),
                            AppBar.OverflowAction(title = stringResource(MR.strings.action_edit_categories), onClick = screenModel::showChangeCategoryDialog),
                            AppBar.OverflowAction(title = stringResource(MR.strings.action_web_view), onClick = onWebView),
                            AppBar.OverflowAction(title = stringResource(MR.strings.action_share), onClick = onShare),
                        ),
                    )
                },
                actionModeActions = {
                    AppBarActions(
                        actions = listOf(
                            AppBar.Action(title = stringResource(MR.strings.action_select_all), icon = Icons.Outlined.SelectAll, onClick = screenModel::selectAll),
                            AppBar.Action(title = stringResource(MR.strings.action_select_inverse), icon = Icons.Outlined.FlipToBack, onClick = screenModel::invertSelection),
                        ),
                    )
                },
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            MangaBottomActionMenu(
                visible = selectionMode,
                onBookmarkClicked = { screenModel.bookmarkSelected(true) },
                onRemoveBookmarkClicked = { screenModel.bookmarkSelected(false) },
                onMarkAsReadClicked = { screenModel.markSelectedRead(true) },
                onMarkAsUnreadClicked = { screenModel.markSelectedRead(false) },
                onMarkPreviousAsReadClicked = { screenModel.markPreviousRead(true) },
            )
        },
    ) { contentPadding ->
        if (isTabletUi()) {
            TwoPanelBox(
                startContent = {
                    LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding())) {
                        novelHeaderItems(state, screenModel, onWebView, onShare, isTabletUi = true)
                    }
                },
                endContent = {
                    LazyColumn(contentPadding = contentPadding) {
                        novelChapterItems(state, screenModel, onChapterClick)
                    }
                },
            )
        } else {
            LazyColumn(contentPadding = contentPadding) {
                novelHeaderItems(state, screenModel, onWebView, onShare, isTabletUi = false)
                novelChapterItems(state, screenModel, onChapterClick)
            }
        }
    }
}

private fun LazyListScope.novelHeaderItems(
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
    onWebView: () -> Unit,
    onShare: () -> Unit,
    isTabletUi: Boolean,
) {
    val novel = state.novel
    item(key = "info") {
        NovelInfoBox(
            isTabletUi = isTabletUi,
            appBarPadding = PaddingValues().calculateTopPadding(),
            novel = novel,
            sourceName = state.sourceName,
            onCoverClick = {},
        )
    }
    item(key = "actions") {
        NovelActionRow(
            favorite = novel.favorite,
            onAddToLibraryClicked = screenModel::toggleFavorite,
            onWebViewClicked = state.sourceUrl?.let { { onWebView() } },
            onShareClicked = state.sourceUrl?.let { { onShare() } },
        )
    }
    item(key = "description") {
        ExpandableMangaDescription(
            defaultExpandState = false,
            description = novel.description,
            tagsProvider = { novel.genre },
            notes = "",
            onTagSearch = {},
            onCopyTagToClipboard = {},
            onEditNotes = {},
        )
    }
    // TODO(S8): merge source-switcher chips slot
    item(key = "chapter-header") {
        ChapterHeader(
            enabled = !state.selectionMode,
            chapterCount = state.chapters.size,
            missingChapterCount = 0,
            onClick = screenModel::showChapterSettingsDialog,
        )
    }
}

private fun LazyListScope.novelChapterItems(
    state: NovelDetailsState.Loaded,
    screenModel: NovelDetailsScreenModel,
    onChapterClick: (NovelChapter) -> Unit,
) {
    items(items = state.chapters, key = { "chapter-${it.id}" }) { chapter ->
        val selected = chapter.id in state.selection
        MangaChapterListItem(
            title = chapterTitle(chapter, state.hideChapterTitles),
            date = chapter.dateUpload.takeIf { it > 0L }?.let { DateFormat.getDateInstance(DateFormat.SHORT).format(Date(it)) },
            readProgress = (chapter.lastTextProgress / 100L).toInt().takeIf { !chapter.read && it > 0 }?.let { "$it%" },
            scanlator = null,
            read = chapter.read,
            bookmark = chapter.bookmark,
            selected = selected,
            downloadIndicatorEnabled = false,
            downloadStateProvider = { Download.State.NOT_DOWNLOADED },
            downloadProgressProvider = { 0 },
            chapterSwipeStartAction = LibraryPreferences.ChapterSwipeAction.Disabled,
            chapterSwipeEndAction = LibraryPreferences.ChapterSwipeAction.Disabled,
            onLongClick = { screenModel.toggleSelection(chapter.id, fromLongPress = true) },
            onClick = {
                if (state.selectionMode) screenModel.toggleSelection(chapter.id, fromLongPress = false) else onChapterClick(chapter)
            },
            onDownloadClick = null,
            onChapterSwipe = {},
        )
    }
}

private fun chapterTitle(chapter: NovelChapter, hideTitles: Boolean): String =
    if (hideTitles && chapter.chapterNumber >= 0.0) {
        "Chapter ${formatChapterNumber(chapter.chapterNumber)}"
    } else {
        chapter.name
    }

private fun formatChapterNumber(number: Double): String =
    if (number % 1.0 == 0.0) number.toInt().toString() else number.toString()

@Suppress("unused")
private fun Novel.titleOrUntitled(): String = title.ifBlank { "Untitled" }
