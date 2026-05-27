package yokai.presentation.novel.probe

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.novel.host.LnPluginHost
import yokai.novel.host.LnPluginLoader
import yokai.novel.install.LnPluginInstaller
import yokai.novel.source.NovelSourceManager
import yokai.presentation.component.ReikaiTopBar

private val PRETTY_JSON = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    isLenient = true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LnPluginHostProbeScreen() {
    val context = LocalContext.current
    val networkHelper = remember { Injekt.get<NetworkHelper>() }
    val host = remember { LnPluginHost(context, networkHelper.client) }
    val loader = remember { Injekt.get<LnPluginLoader>() }
    val installer = remember { Injekt.get<LnPluginInstaller>() }
    val manager = remember { Injekt.get<NovelSourceManager>() }
    val scope = rememberCoroutineScope()
    val backPress = LocalBackPress.current

    DisposableEffect(host) {
        onDispose { host.destroy() }
    }

    var pluginUrl by remember { mutableStateOf("") }
    // Captured from the load result. This is the plugin's canonical id (e.g. "novelbin"), used
    // for every host method call after load. The user no longer types it.
    var loadedPluginId by remember { mutableStateOf<String?>(null) }
    var optionsJson by remember {
        mutableStateOf(
            // Default works for any lnreader source that doesn't read filters from
            // PopularNovelsOptions. novelbin etc. need real filter values pasted here.
            "{}",
        )
    }
    var novelPath by remember { mutableStateOf("") }
    var chapterPath by remember { mutableStateOf("") }
    var searchQuery by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Idle") }
    var output by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var registeredIds by remember { mutableStateOf<List<String>>(emptyList()) }

    // On screen open, re-load every previously-installed plugin into this fresh host. Registers
    // each in NovelSourceManager, so any subsequent product code can find them via the manager.
    LaunchedEffect(host) {
        try {
            val loaded = installer.loadInstalled(host)
            registeredIds = loaded.map { it.id }
            if (loaded.isNotEmpty()) {
                loadedPluginId = loaded.last().id
            }
        } catch (_: Throwable) {
            // Logged inside the installer; don't block the probe.
        }
    }

    fun run(label: String, block: suspend () -> Any?) {
        if (busy) return
        busy = true
        status = "$label running…"
        output = ""
        scope.launch {
            try {
                val result = block()
                status = "$label OK"
                output = pretty(result)
            } catch (e: Throwable) {
                status = "$label failed"
                output = "${e.javaClass.simpleName}: ${e.message}\n\n${e.stackTraceToString()}"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        topBar = {
            ReikaiTopBar(
                title = "LN plugin host probe",
                navigationIcon = {
                    IconButton(onClick = { backPress?.invoke() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = pluginUrl,
                onValueChange = { pluginUrl = it },
                label = { Text("Plugin .js URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = optionsJson,
                onValueChange = { optionsJson = it },
                label = { Text("popularNovels options JSON") },
                singleLine = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = novelPath,
                onValueChange = { novelPath = it },
                label = { Text("Novel path (for parseNovel)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = chapterPath,
                onValueChange = { chapterPath = it },
                label = { Text("Chapter path (for parseChapter)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Search query") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    enabled = !busy && pluginUrl.isNotBlank(),
                    onClick = {
                        run("loadPlugin") {
                            val src = loader.fetchSource(pluginUrl, forceRefresh = true)
                            // The pluginId passed here is only used as the @libs/storage scope
                            // prefix; the host keys its plugin registry by plugin.id (returned
                            // in the info below). Derive a stable scope id from the URL filename.
                            val scopeId = pluginUrl.substringAfterLast('/').substringBeforeLast('.')
                            val info = host.loadPlugin(scopeId, src)
                            loadedPluginId = info.id
                            info
                        }
                    },
                ) { Text("Load plugin") }
                Button(
                    enabled = !busy && pluginUrl.isNotBlank(),
                    onClick = {
                        run("install") {
                            val source = installer.installFromUrl(host, pluginUrl)
                            loadedPluginId = source.id
                            registeredIds = manager.getAll().map { it.id }
                            mapOf(
                                "installed" to source.id,
                                "registered" to registeredIds,
                            )
                        }
                    },
                ) { Text("Install + register") }
                Button(
                    enabled = !busy && loadedPluginId != null,
                    onClick = {
                        run("popularNovels(1)") {
                            host.popularNovels(loadedPluginId!!, 1, optionsJson.ifBlank { "{}" })
                        }
                    },
                ) { Text("popularNovels(1)") }
                Button(
                    enabled = !busy && loadedPluginId != null && novelPath.isNotBlank(),
                    onClick = { run("parseNovel") { host.parseNovel(loadedPluginId!!, novelPath) } },
                ) { Text("parseNovel") }
                Button(
                    enabled = !busy && loadedPluginId != null && chapterPath.isNotBlank(),
                    onClick = {
                        run("parseChapter") {
                            val text = host.parseChapter(loadedPluginId!!, chapterPath)
                            // Truncate huge chapter bodies in the UI; show full length count.
                            "length=${text.length}\n\n" + text.take(8_000)
                        }
                    },
                ) { Text("parseChapter") }
                Button(
                    enabled = !busy && loadedPluginId != null && searchQuery.isNotBlank(),
                    onClick = {
                        run("searchNovels") { host.searchNovels(loadedPluginId!!, searchQuery, 1) }
                    },
                ) { Text("searchNovels") }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = buildString {
                    append(status)
                    if (loadedPluginId != null) append("  •  active: ${loadedPluginId}")
                    if (registeredIds.isNotEmpty()) {
                        append("  •  registered: ${registeredIds.size} (${registeredIds.joinToString()})")
                    }
                },
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            SelectionContainer(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = output,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun pretty(value: Any?): String = when (value) {
    null -> "null"
    is String -> value
    is JsonElement -> PRETTY_JSON.encodeToString(JsonElement.serializer(), value)
    else -> value.toString()
}
