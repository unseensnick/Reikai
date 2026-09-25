package reikai.presentation.browse.extension.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import eu.kanade.presentation.browse.ExtensionUninstallConfirmation
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.TextPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Settings
import reikai.novel.source.NovelSettings
import reikai.presentation.browse.browseLanguageLabel
import reikai.presentation.browse.components.NovelSourceIcon
import reikai.presentation.novel.browse.NovelSourceSettingsSheet
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * An installed light-novel plugin's page, opened by tapping its Extensions row as a manga extension's
 * opens Mihon's `ExtensionDetailsScreen`. A plugin is not a package the system can remove, so its
 * Uninstall is confirmed here.
 */
data class NovelPluginDetailsScreen(private val pluginId: String) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = assistedMetroViewModel<NovelPluginDetailsViewModel, NovelPluginDetailsViewModel.Factory> {
            create(pluginId)
        }
        val state by viewModel.state.collectAsStateWithLifecycle()

        when (val current = state) {
            NovelPluginDetailsViewModel.State.Loading -> LoadingScreen()
            NovelPluginDetailsViewModel.State.Uninstalled -> {
                LaunchedEffect(Unit) { navigator.pop() }
                EmptyScreen(MR.strings.empty_screen)
            }
            is NovelPluginDetailsViewModel.State.Success -> Scaffold(
                topBar = { scrollBehavior ->
                    AppBar(
                        title = stringResource(MR.strings.label_extension_info),
                        navigateUp = navigator::pop,
                        scrollBehavior = scrollBehavior,
                    )
                },
            ) { contentPadding ->
                NovelPluginDetails(
                    state = current,
                    contentPadding = contentPadding,
                    onOpenSite = {
                        navigator.push(WebViewScreen(url = current.plugin.site, initialTitle = current.plugin.name))
                    },
                    onUninstall = viewModel::uninstall,
                )
            }
        }
    }
}

@Composable
private fun NovelPluginDetails(
    state: NovelPluginDetailsViewModel.State.Success,
    contentPadding: PaddingValues,
    onOpenSite: () -> Unit,
    onUninstall: () -> Unit,
) {
    val plugin = state.plugin
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var confirmUninstall by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val settings = plugin.settings as? NovelSettings.LnSchema

    Column(
        modifier = Modifier
            .padding(contentPadding)
            .verticalScroll(rememberScrollState()),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(MaterialTheme.padding.medium),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NovelSourceIcon(plugin.iconUrl, size = 112.dp)
            Text(
                text = plugin.name,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(text = plugin.id, style = MaterialTheme.typography.bodySmall)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MaterialTheme.padding.medium, vertical = MaterialTheme.padding.small),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            InfoText(state.version, stringResource(MR.strings.ext_info_version), Modifier.weight(1f))
            InfoText(
                browseLanguageLabel(plugin.lang, context),
                stringResource(MR.strings.ext_info_language),
                Modifier.weight(1f),
            )
        }

        Row(
            modifier = Modifier.padding(
                horizontal = MaterialTheme.padding.medium,
                vertical = MaterialTheme.padding.small,
            ),
            horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.medium),
        ) {
            OutlinedButton(modifier = Modifier.weight(1f), onClick = { confirmUninstall = true }) {
                Text(stringResource(MR.strings.ext_uninstall))
            }
            if (plugin.site.isNotEmpty()) {
                Button(modifier = Modifier.weight(1f), onClick = onOpenSite) {
                    Text(stringResource(MR.strings.website))
                }
            }
        }

        HorizontalDivider(modifier = Modifier.padding(top = MaterialTheme.padding.small))

        if (state.repoUrl != null) {
            TextPreferenceWidget(
                title = stringResource(MR.strings.repo_kind_plugins),
                subtitle = state.repoUrl,
                onPreferenceClick = { uriHandler.openUri(repoPage(state.repoUrl)) },
            )
        }
        if (settings != null) {
            TextPreferenceWidget(
                title = stringResource(MR.strings.action_settings),
                icon = MaterialSymbols.Rounded.Settings,
                onPreferenceClick = { showSettings = true },
            )
        }
    }

    if (confirmUninstall) {
        ExtensionUninstallConfirmation(
            extensionName = plugin.name,
            onClickConfirm = onUninstall,
            onDismissRequest = { confirmUninstall = false },
        )
    }
    if (showSettings && settings != null) {
        NovelSourceSettingsSheet(settings, onDismiss = { showSettings = false })
    }
}

@Composable
private fun InfoText(primary: String, secondary: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = primary, style = MaterialTheme.typography.titleSmall, textAlign = TextAlign.Center)
        Text(
            text = secondary,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
        )
    }
}

/** A repo's GitHub page when its index is served raw from GitHub, as Mihon's Open repo resolves one. */
private fun repoPage(indexUrl: String): String =
    Regex("""https://raw.githubusercontent.com/(.+?)/(.+?)/.+""").find(indexUrl)
        ?.let { "https://github.com/${it.groupValues[1]}/${it.groupValues[2]}" }
        ?: indexUrl
