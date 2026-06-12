package reikai.presentation.browse.repo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoreDialog
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoreScreenState
import eu.kanade.presentation.more.settings.screen.browse.ExtensionStoresScreenModel
import eu.kanade.presentation.more.settings.screen.browse.components.ExtensionStoreCreateDialog
import eu.kanade.presentation.more.settings.screen.browse.components.ExtensionStoreDeleteDialog
import eu.kanade.presentation.more.settings.screen.browse.components.ExtensionStoresListItem
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.copyToClipboard
import eu.kanade.tachiyomi.util.system.openInBrowser
import reikai.presentation.browse.components.BrowseSectionHeader
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.components.material.topSmallPaddingValues
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.plus

/**
 * Combined repo manager reached from the Browse → Extensions overflow (P5 S3a). One screen, two
 * header sections: Manga reuses Mihon's rich [ExtensionStoresScreenModel] + repo cards; Light novels
 * is a thin list over [reikai.domain.novel.NovelPreferences.addedRepoUrls]. Both reuse Mihon's
 * add / delete dialogs. Per-section add (not a chip) because the add action must target a bucket.
 */
class RepoStoresScreen : Screen() {

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val navigator = LocalNavigator.currentOrThrow

        val mangaModel = rememberScreenModel { ExtensionStoresScreenModel() }
        val mangaState by mangaModel.state.collectAsState()
        val lnModel = rememberScreenModel { NovelRepoStoresScreenModel() }
        val lnRepos by lnModel.repos.collectAsState()

        var lnCreateOpen by remember { mutableStateOf(false) }
        var lnDeleteTarget by remember { mutableStateOf<String?>(null) }

        val mangaStores = (mangaState as? ExtensionStoreScreenState.Success)?.stores.orEmpty()
        val mangaDialog = (mangaState as? ExtensionStoreScreenState.Success)?.dialog

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.repos),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { paddingValues ->
            LazyColumn(
                contentPadding = paddingValues + topSmallPaddingValues,
                verticalArrangement = Arrangement.spacedBy(MaterialTheme.padding.small),
                modifier = Modifier.padding(horizontal = MaterialTheme.padding.medium),
            ) {
                item(key = "repo-manga-header") {
                    BrowseSectionHeader(title = stringResource(MR.strings.content_type_manga)) {
                        IconButton(onClick = { mangaModel.showDialog(ExtensionStoreDialog.Create()) }) {
                            Icon(Icons.Outlined.Add, contentDescription = stringResource(MR.strings.action_add))
                        }
                    }
                }
                items(items = mangaStores, key = { "repo-manga-${it.indexUrl}" }) { store ->
                    ExtensionStoresListItem(
                        store = store,
                        onOpenWebsite = { store.contact.website.let(context::openInBrowser) },
                        onOpenDiscord = { store.contact.discord?.let(context::openInBrowser) },
                        onCopy = { context.copyToClipboard(store.indexUrl, store.indexUrl) },
                        onDelete = { mangaModel.showDialog(ExtensionStoreDialog.Delete(store)) },
                    )
                }

                item(key = "repo-ln-header") {
                    BrowseSectionHeader(title = stringResource(MR.strings.ln_repos)) {
                        IconButton(onClick = { lnCreateOpen = true }) {
                            Icon(Icons.Outlined.Add, contentDescription = stringResource(MR.strings.action_add))
                        }
                    }
                }
                items(items = lnRepos, key = { "repo-ln-$it" }) { url ->
                    LnRepoRow(url = url, onDelete = { lnDeleteTarget = url })
                }
            }
        }

        when (mangaDialog) {
            null -> {}
            is ExtensionStoreDialog.Create -> ExtensionStoreCreateDialog(
                onDismissRequest = mangaModel::dismissDialog,
                onCreate = { mangaModel.createRepo(it) },
                storeIndexUrls = mangaStores.map { it.indexUrl }.toSet(),
                processing = mangaDialog.processing,
                errorMessage = mangaDialog.errorMessage,
            )
            is ExtensionStoreDialog.Delete -> ExtensionStoreDeleteDialog(
                onDismissRequest = mangaModel::dismissDialog,
                onDelete = { mangaModel.deleteRepo(mangaDialog.store.indexUrl) },
                storeName = mangaDialog.store.name,
                storeIndexUrl = mangaDialog.store.indexUrl,
            )
            is ExtensionStoreDialog.Confirm -> {}
        }

        if (lnCreateOpen) {
            ExtensionStoreCreateDialog(
                onDismissRequest = { lnCreateOpen = false },
                onCreate = {
                    lnModel.addRepo(it)
                    lnCreateOpen = false
                },
                storeIndexUrls = lnRepos.toSet(),
                processing = false,
                errorMessage = null,
            )
        }

        lnDeleteTarget?.let { url ->
            ExtensionStoreDeleteDialog(
                onDismissRequest = { lnDeleteTarget = null },
                onDelete = { lnModel.deleteRepo(url) },
                storeName = url,
                storeIndexUrl = url,
            )
        }
    }
}

@Composable
private fun LnRepoRow(
    url: String,
    onDelete: () -> Unit,
) {
    ElevatedCard {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = MaterialTheme.padding.medium),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                text = url,
                modifier = Modifier
                    .weight(1f)
                    .padding(vertical = MaterialTheme.padding.small),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium,
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(MR.strings.action_delete))
            }
        }
    }
}
