package yokai.presentation.novel.repo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.novel.NovelPreferences
import yokai.novel.host.LnPluginHost
import yokai.novel.install.LnPluginInstaller
import yokai.novel.registry.LnRegistryEntry

private const val DEFAULT_REPO_HINT =
    "e.g. https://raw.githubusercontent.com/.../plugins.min.json"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LnRepoBrowseScreen() {
    val context = LocalContext.current
    val networkHelper = remember { Injekt.get<NetworkHelper>() }
    val installer = remember { Injekt.get<LnPluginInstaller>() }
    val prefs = remember { Injekt.get<NovelPreferences>() }
    val host = remember { LnPluginHost(context, networkHelper.client) }
    val scope = rememberCoroutineScope()
    val backPress = LocalBackPress.current

    DisposableEffect(host) { onDispose { host.destroy() } }

    val installedUrls by prefs.installedPluginUrls().collectAsState()

    var repoUrl by remember { mutableStateOf("") }
    var entries by remember { mutableStateOf<List<LnRegistryEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Per-entry busy flags so a slow install on one plugin doesn't block clicks on others.
    var busyIds by remember { mutableStateOf(emptySet<String>()) }

    // Re-load every persisted plugin into this fresh host on screen open. Without this, an
    // entry that's already in installedPluginUrls would still look "Available" until an
    // install completes.
    LaunchedEffect(host) {
        try {
            installer.loadInstalled(host)
        } catch (_: Throwable) {
            // logged in the installer; we still want the screen to render
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LN plugin repo browse") },
                navigationIcon = {
                    IconButton(onClick = { backPress?.invoke() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = repoUrl,
                onValueChange = { repoUrl = it; error = null },
                label = { Text("Repo URL (plugins.min.json)") },
                placeholder = { Text(DEFAULT_REPO_HINT) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    enabled = !loading && repoUrl.isNotBlank(),
                    onClick = {
                        scope.launch {
                            loading = true
                            error = null
                            entries = emptyList()
                            try {
                                entries = installer.fetchRepo(repoUrl)
                            } catch (e: Throwable) {
                                error = "${e.javaClass.simpleName}: ${e.message ?: ""}"
                            } finally {
                                loading = false
                            }
                        }
                    },
                ) { Text("Load repo") }
                Spacer(Modifier.size(12.dp))
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else if (entries.isNotEmpty()) {
                    Text(
                        text = "${entries.size} plugin${if (entries.size == 1) "" else "s"}  •  ${installedUrls.size} installed",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(text = it, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = entries, key = { it.id + it.url }) { entry ->
                    PluginRow(
                        entry = entry,
                        installed = entry.url in installedUrls,
                        busy = entry.id in busyIds,
                        onInstall = {
                            scope.launch {
                                busyIds = busyIds + entry.id
                                try {
                                    installer.installFromUrl(host, entry.url)
                                } catch (e: Throwable) {
                                    error = "install ${entry.id} failed: ${e.message ?: e.javaClass.simpleName}"
                                } finally {
                                    busyIds = busyIds - entry.id
                                }
                            }
                        },
                        onUninstall = {
                            scope.launch {
                                busyIds = busyIds + entry.id
                                try {
                                    installer.uninstall(entry.id, entry.url)
                                } catch (e: Throwable) {
                                    error = "uninstall ${entry.id} failed: ${e.message ?: e.javaClass.simpleName}"
                                } finally {
                                    busyIds = busyIds - entry.id
                                }
                            }
                        },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun PluginRow(
    entry: LnRegistryEntry,
    installed: Boolean,
    busy: Boolean,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${entry.id}  •  v${entry.version}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = {},
                    enabled = false,
                    label = { Text(entry.lang, style = MaterialTheme.typography.labelSmall) },
                    colors = AssistChipDefaults.assistChipColors(),
                )
                if (installed) {
                    AssistChip(
                        onClick = {},
                        enabled = false,
                        label = { Text("Installed", style = MaterialTheme.typography.labelSmall) },
                        colors = AssistChipDefaults.assistChipColors(
                            disabledLabelColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }
        if (installed) {
            OutlinedButton(enabled = !busy, onClick = onUninstall) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else Text("Uninstall")
            }
        } else {
            Button(enabled = !busy, onClick = onInstall) {
                if (busy) CircularProgressIndicator(modifier = Modifier.size(16.dp))
                else Text("Install")
            }
        }
    }
}
