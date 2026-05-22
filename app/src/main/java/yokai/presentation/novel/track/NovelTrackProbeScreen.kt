package yokai.presentation.novel.track

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.FilterChip
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import eu.kanade.tachiyomi.data.track.TrackService
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.NovelTrackRepository
import yokai.domain.novel.models.NovelTrack
import yokai.presentation.manga.components.MangaCover
import yokai.presentation.manga.components.MangaCoverRatio

/**
 * Debug-only probe. Validates AniList NOVEL search + novel_tracks persistence end-to-end:
 *  1. Pick a favorited novel from the library.
 *  2. Run an AniList NOVEL-scoped search using the novel's title (editable).
 *  3. Tap a result to upsert a NovelTrack row.
 *  4. The bottom panel re-renders from observeByNovelId — confirms read-back from the table.
 *
 * Real UI on the novel detail screen lands in a later slice; this screen exists so the plumbing
 * is reviewable in isolation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelTrackProbeScreen() {
    val novelRepo = remember { Injekt.get<NovelRepository>() }
    val trackRepo = remember { Injekt.get<NovelTrackRepository>() }
    val trackManager = remember { Injekt.get<TrackManager>() }
    val anilist = trackManager.aniList
    val mal = trackManager.myAnimeList
    val backPress = LocalBackPress.current
    val scope = rememberCoroutineScope()

    var tracker by remember { mutableStateOf<TrackService>(anilist) }
    val trackerLabel = if (tracker === anilist) "AniList" else "MAL"

    val novels by novelRepo.getAllAsFlow().collectAsState(initial = emptyList())
    val favorites = remember(novels) { novels.filter { it.favorite }.sortedBy { it.title.lowercase() } }

    var selected by remember(favorites) { mutableStateOf(favorites.firstOrNull()) }
    var query by remember(selected) { mutableStateOf(selected?.title.orEmpty()) }
    var dropdownOpen by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<TrackSearch>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }

    val tracks by (selected?.id?.let { trackRepo.observeByNovelId(it) } ?: flowOf(emptyList()))
        .collectAsState(initial = emptyList())

    LaunchedEffect(tracker, anilist.isLogged, mal.isLogged) {
        error = if (!tracker.isLogged) {
            "$trackerLabel is not logged in. Settings, Tracking, $trackerLabel, sign in, then retry."
        } else null
        results = emptyList()
        status = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LN track probe", style = MaterialTheme.typography.titleMedium) },
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
            if (favorites.isEmpty()) {
                Text("No favorited novels. Save one from Debug, LN browse or LN library.")
                return@Column
            }

            Text("Tracker", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChip(
                    selected = tracker === anilist,
                    onClick = { tracker = anilist },
                    label = { Text("AniList") },
                )
                Spacer(Modifier.width(8.dp))
                FilterChip(
                    selected = tracker === mal,
                    onClick = { tracker = mal },
                    label = { Text("MAL") },
                )
            }
            Spacer(Modifier.height(8.dp))

            Text("Novel", style = MaterialTheme.typography.labelLarge)
            Box {
                OutlinedButton(onClick = { dropdownOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(selected?.title ?: "(pick one)")
                }
                DropdownMenu(expanded = dropdownOpen, onDismissRequest = { dropdownOpen = false }) {
                    favorites.forEach { novel ->
                        DropdownMenuItem(
                            text = { Text(novel.title) },
                            onClick = {
                                selected = novel
                                dropdownOpen = false
                            },
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search query") },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = {
                        if (loading || selected == null) return@Button
                        scope.launch {
                            loading = true; error = null; status = null; results = emptyList()
                            try {
                                results = when (tracker) {
                                    anilist -> anilist.searchNovels(query)
                                    mal -> mal.searchNovels(query)
                                    else -> emptyList()
                                }
                                if (results.isEmpty()) status = "no results"
                            } catch (e: Throwable) {
                                error = "${e.javaClass.simpleName}: ${e.message ?: ""}"
                            } finally { loading = false }
                        }
                    },
                    enabled = !loading && query.isNotBlank(),
                ) { Text("Search $trackerLabel") }
                Spacer(Modifier.width(12.dp))
                if (loading) CircularProgressIndicator()
                status?.let {
                    Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            error?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            Spacer(Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))

            if (results.isNotEmpty()) {
                Text("Results (tap to bind)", style = MaterialTheme.typography.labelLarge)
                LazyColumn(modifier = Modifier.fillMaxWidth().height(280.dp)) {
                    items(items = results, key = { it.media_id }) { r ->
                        SearchResultRow(
                            result = r,
                            onPick = {
                                val novel = selected ?: return@SearchResultRow
                                val novelId = novel.id ?: return@SearchResultRow
                                scope.launch {
                                    val id = trackRepo.upsert(toNovelTrack(novelId, r, tracker.id))
                                    status = if (id != null) "bound $trackerLabel row id=$id" else "upsert failed"
                                }
                            },
                        )
                    }
                }
                HorizontalDivider()
            }

            Spacer(Modifier.height(8.dp))
            Text("Stored tracker rows for this novel", style = MaterialTheme.typography.labelLarge)
            if (tracks.isEmpty()) {
                Text("(none)", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(items = tracks, key = { it.id ?: 0L }) { t ->
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text(t.title, style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "sync_id=${t.syncId}  •  remote_id=${t.remoteId}  •  ${t.remoteUrl}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultRow(result: TrackSearch, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPick() }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MangaCover(
            data = result.cover_url,
            ratio = MangaCoverRatio.BOOK,
            modifier = Modifier.width(48.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(result.title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = "AL id=${result.media_id}  •  ${result.publishing_type.ifBlank { "?" }}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun toNovelTrack(novelId: Long, search: TrackSearch, syncId: Long): NovelTrack =
    NovelTrack(
        novelId = novelId,
        syncId = syncId,
        remoteId = search.media_id,
        title = search.title,
        totalChapters = search.total_chapters,
        remoteUrl = search.tracking_url,
    )
