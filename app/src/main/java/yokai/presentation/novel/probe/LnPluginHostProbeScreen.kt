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
import androidx.compose.material3.TopAppBar
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
    val loader = remember { LnPluginLoader(context, networkHelper.client) }
    val scope = rememberCoroutineScope()
    val backPress = LocalBackPress.current

    DisposableEffect(host) {
        onDispose { host.destroy() }
    }

    var pluginUrl by remember { mutableStateOf("") }
    var pluginId by remember { mutableStateOf("") }
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

    // Auto-derive plugin id from URL filename when the user hasn't overridden it.
    LaunchedEffect(pluginUrl) {
        val derived = pluginUrl.substringAfterLast('/').substringBeforeLast('.')
        if (pluginId.isBlank() || pluginId == derivedFrom(pluginUrl, last = true)) {
            pluginId = derived
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
            TopAppBar(
                title = { Text("LN plugin host probe") },
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
                value = pluginUrl,
                onValueChange = { pluginUrl = it },
                label = { Text("Plugin .js URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = pluginId,
                onValueChange = { pluginId = it },
                label = { Text("Plugin id") },
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
                    enabled = !busy && pluginUrl.isNotBlank() && pluginId.isNotBlank(),
                    onClick = {
                        run("loadPlugin") {
                            val src = loader.fetchSource(pluginUrl, forceRefresh = true)
                            host.loadPlugin(pluginId, src)
                        }
                    },
                ) { Text("Load plugin") }
                Button(
                    enabled = !busy && pluginId.isNotBlank(),
                    onClick = {
                        run("popularNovels(1)") {
                            host.popularNovels(pluginId, 1, optionsJson.ifBlank { "{}" })
                        }
                    },
                ) { Text("popularNovels(1)") }
                Button(
                    enabled = !busy && pluginId.isNotBlank() && novelPath.isNotBlank(),
                    onClick = { run("parseNovel") { host.parseNovel(pluginId, novelPath) } },
                ) { Text("parseNovel") }
                Button(
                    enabled = !busy && pluginId.isNotBlank() && chapterPath.isNotBlank(),
                    onClick = {
                        run("parseChapter") {
                            val text = host.parseChapter(pluginId, chapterPath)
                            // Truncate huge chapter bodies in the UI; show full length count.
                            "length=${text.length}\n\n" + text.take(8_000)
                        }
                    },
                ) { Text("parseChapter") }
                Button(
                    enabled = !busy && pluginId.isNotBlank() && searchQuery.isNotBlank(),
                    onClick = {
                        run("searchNovels") { host.searchNovels(pluginId, searchQuery, 1) }
                    },
                ) { Text("searchNovels") }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                text = status,
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = output,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            )
        }
    }
}

private fun derivedFrom(url: String, last: Boolean) =
    if (last) url.substringAfterLast('/').substringBeforeLast('.') else url

private fun pretty(value: Any?): String = when (value) {
    null -> "null"
    is String -> value
    is JsonElement -> PRETTY_JSON.encodeToString(JsonElement.serializer(), value)
    else -> value.toString()
}
