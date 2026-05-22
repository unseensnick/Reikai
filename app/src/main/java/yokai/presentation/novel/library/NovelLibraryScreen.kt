package yokai.presentation.novel.library

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.setting.controllers.debug.NovelDetailsController
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.compose.LocalRouter
import eu.kanade.tachiyomi.util.compose.currentOrThrow
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.novel.NovelRepository
import yokai.presentation.manga.components.MangaCover
import yokai.presentation.manga.components.MangaCoverRatio

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelLibraryScreen() {
    val backPress = LocalBackPress.current
    val favoritesCount = collectFavoritesCount()
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "LN library  •  $favoritesCount",
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { backPress?.invoke() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding -> NovelLibraryContent(padding) }
}

/**
 * The body of the library screen without its own Scaffold or TopAppBar. Used by
 * [NovelLibraryScreen] (debug entry with back arrow) and by the top-level Novels tab home
 * (which wants its own TopAppBar with an overflow menu instead of a back arrow).
 */
@Composable
fun NovelLibraryContent(padding: PaddingValues) {
    val repo = remember { Injekt.get<NovelRepository>() }
    val router = LocalRouter.currentOrThrow

    val novels by repo.getAllAsFlow().collectAsState(initial = emptyList())
    val favorites = remember(novels) { novels.filter { it.favorite }.sortedBy { it.title.lowercase() } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        if (favorites.isEmpty()) {
            Text("Library is empty. Save a novel from the browse screen.")
            return@Column
        }
        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(items = favorites, key = { it.id ?: 0L }) { novel ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            router.pushController(
                                NovelDetailsController(
                                    sourceId = novel.source,
                                    novelUrl = novel.url,
                                ).withFadeTransaction(),
                            )
                        }
                        .padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MangaCover(
                        data = novel.thumbnailUrl,
                        ratio = MangaCoverRatio.BOOK,
                        modifier = Modifier.width(56.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Text(novel.title, style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = buildString {
                                append(novel.source)
                                novel.author?.takeIf { it.isNotBlank() }?.let { append("  •  by ").append(it) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun collectFavoritesCount(): Int {
    val repo = remember { Injekt.get<NovelRepository>() }
    val novels by repo.getAllAsFlow().collectAsState(initial = emptyList())
    return novels.count { it.favorite }
}
