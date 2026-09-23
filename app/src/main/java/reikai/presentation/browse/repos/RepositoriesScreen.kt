package reikai.presentation.browse.repos

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.openInBrowser
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.ContentCopy
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Public
import mihon.icons.materialsymbols.rounded.Refresh
import mihon.icons.simpleicons.Discord
import mihon.icons.simpleicons.SimpleIcons
import reikai.domain.extension.RepoStatus
import reikai.presentation.components.ContentTypeBadge
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.Pill
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen
import tachiyomi.presentation.core.util.plus
import tachiyomi.presentation.core.util.shouldExpandFAB

/** Every extension store and LN plugin repo; [url] is an address a deep link asks to add. */
class RepositoriesScreen(private val url: String? = null) : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow
        val model = metroViewModel<RepositoriesViewModel>()
        val state by model.state.collectAsStateWithLifecycle()
        val listState = rememberLazyListState()

        LaunchedEffect(url) {
            url?.let(model::showAdd)
        }

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    titleContent = { RepositoriesTitle(count = state.cards.size) },
                    navigateUp = navigator::pop,
                    actions = {
                        IconButton(onClick = model::refresh) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.Refresh,
                                contentDescription = stringResource(MR.strings.action_webview_refresh),
                            )
                        }
                    },
                    scrollBehavior = scrollBehavior,
                )
            },
            floatingActionButton = {
                SmallExtendedFloatingActionButton(
                    text = { Text(text = stringResource(MR.strings.action_add_repo)) },
                    icon = { Icon(imageVector = MaterialSymbols.Rounded.Add, contentDescription = null) },
                    onClick = { model.showAdd() },
                    expanded = listState.shouldExpandFAB(),
                )
            },
        ) { contentPadding ->
            when {
                state.isLoading -> LoadingScreen(Modifier.padding(contentPadding))
                state.cards.isEmpty() -> EmptyScreen(
                    stringRes = MR.strings.ext_no_repos,
                    modifier = Modifier.padding(contentPadding),
                )
                else -> Column(Modifier.padding(top = contentPadding.calculateTopPadding())) {
                    if (state.isRefreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()) +
                            PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(state.cards, key = { it.address }) { card ->
                            RepoCard(
                                card = card,
                                showTypeBadges = state.showTypeBadges,
                                onOpen = { model.openDetails(card) },
                                onOpenWebsite = { card.website?.let(context::openInBrowser) },
                                onCopy = { context.copyToClipboard(card.address, card.address) },
                                onRemove = { model.confirmRemove(card) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                }
            }
        }

        state.details?.let { card ->
            RepoDetailsSheet(
                card = card,
                onOpenDiscord = { card.discord?.let(context::openInBrowser) },
                onDismissRequest = model::closeDetails,
            )
        }

        when (val dialog = state.dialog) {
            null -> {}
            is RepoDialog.Add -> AddRepoDialog(
                dialog = dialog,
                addedAddresses = state.cards.mapTo(HashSet()) { it.address },
                onAdd = model::add,
                onDismissRequest = model::dismissDialog,
            )
            is RepoDialog.Remove -> AlertDialog(
                onDismissRequest = model::dismissDialog,
                title = { Text(text = stringResource(MR.strings.repo_remove_title)) },
                // The address too, since two plugin repos from one owner or host share a name.
                text = {
                    Text(text = stringResource(MR.strings.repo_remove_body, dialog.card.name, dialog.card.address))
                },
                confirmButton = {
                    TextButton(onClick = { model.remove(dialog.card) }) {
                        Text(text = stringResource(MR.strings.action_ok))
                    }
                },
                dismissButton = {
                    TextButton(onClick = model::dismissDialog) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                },
            )
        }
    }
}

@Composable
private fun RepositoriesTitle(count: Int) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = stringResource(MR.strings.repos),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, false),
        )
        if (count > 0) {
            val pillAlpha = if (isSystemInDarkTheme()) 0.12f else 0.08f
            Pill(
                text = "$count",
                modifier = Modifier.padding(start = 4.dp),
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = pillAlpha),
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun RepoCard(
    card: RepoCardUi,
    showTypeBadges: Boolean,
    onOpen: () -> Unit,
    onOpenWebsite: () -> Unit,
    onCopy: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The download queue's card, so the two card lists read as one family.
    Card(
        onClick = onOpen,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(
                        when (card.format) {
                            RepoFormat.STORE -> MR.strings.repo_kind_store
                            RepoFormat.PLUGINS -> MR.strings.repo_kind_plugins
                        },
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                )
                if (showTypeBadges) card.contentTypes.forEach { ContentTypeBadge(it) }
            }
            Text(
                text = card.name,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = statusText(card),
                style = MaterialTheme.typography.bodySmall,
                color = if (card.status is RepoStatus.Unreachable) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            ) {
                if (card.website != null) {
                    IconButton(onClick = onOpenWebsite) {
                        Icon(
                            imageVector = MaterialSymbols.Rounded.Public,
                            contentDescription = stringResource(MR.strings.action_open_in_browser),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onCopy) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.ContentCopy,
                        contentDescription = stringResource(MR.strings.action_copy_to_clipboard),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = MaterialSymbols.Rounded.Delete,
                        contentDescription = stringResource(MR.strings.action_delete),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun statusText(card: RepoCardUi): String = when (val status = card.status) {
    RepoStatus.Checking -> stringResource(MR.strings.repo_checking)
    is RepoStatus.Unreachable -> stringResource(MR.strings.repo_unreachable)
    is RepoStatus.Reached -> when (card.format) {
        RepoFormat.STORE -> (status.manga + status.novels).let {
            pluralStringResource(MR.plurals.repo_extensions, it, it)
        }
        RepoFormat.PLUGINS -> pluralStringResource(MR.plurals.repo_plugins, status.novels, status.novels)
    }
}

@Composable
private fun RepoDetailsSheet(
    card: RepoCardUi,
    onOpenDiscord: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    AdaptiveSheet(onDismissRequest = onDismissRequest) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(text = card.name, style = MaterialTheme.typography.titleMedium)
            DetailLine(label = stringResource(MR.strings.repo_address), value = card.address)
            card.signingKey?.let { DetailLine(label = stringResource(MR.strings.repo_signing_key), value = it) }
            (card.status as? RepoStatus.Unreachable)?.let {
                DetailLine(label = stringResource(MR.strings.repo_unreachable), value = it.message)
            }
            if (card.discord != null) {
                IconButton(onClick = onOpenDiscord) {
                    Icon(imageVector = SimpleIcons.Discord, contentDescription = null)
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AddRepoDialog(
    dialog: RepoDialog.Add,
    addedAddresses: Set<String>,
    onAdd: (String) -> Unit,
    onDismissRequest: () -> Unit,
) {
    val field = rememberTextFieldState(initialText = dialog.fromLink.orEmpty())
    val address = field.text.toString().trim()
    val alreadyAdded = address in addedAddresses
    // Ready to paste, as Mihon's dialog was; an address a link brought is only read.
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (dialog.fromLink == null) focus.requestFocus() }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.action_add_repo)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (dialog.fromLink != null) Text(text = stringResource(MR.strings.repo_add_deeplink))
                OutlinedTextField(
                    state = field,
                    readOnly = dialog.fromLink != null,
                    label = { Text(text = stringResource(MR.strings.repo_add_input)) },
                    supportingText = when {
                        alreadyAdded -> {
                            { Text(text = stringResource(MR.strings.repo_already_added)) }
                        }
                        dialog.failed == AddRepoOutcome.UNREACHABLE -> {
                            { Text(text = stringResource(MR.strings.repo_add_unreachable)) }
                        }
                        dialog.failed != null -> {
                            { Text(text = stringResource(MR.strings.repo_add_failed)) }
                        }
                        else -> null
                    },
                    isError = alreadyAdded || dialog.failed != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    lineLimits = TextFieldLineLimits.SingleLine,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onAdd(address) },
                enabled = !dialog.processing && address.isNotEmpty() && !alreadyAdded,
            ) {
                Text(
                    text = stringResource(
                        if (dialog.processing) {
                            MR.strings.extensionStoresScreen_addStore_processing
                        } else {
                            MR.strings.action_add
                        },
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissRequest) {
                Text(text = stringResource(MR.strings.action_cancel))
            }
        },
    )
}
