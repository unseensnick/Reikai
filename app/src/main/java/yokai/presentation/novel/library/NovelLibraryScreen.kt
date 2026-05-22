package yokai.presentation.novel.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.novel.NovelRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NovelLibraryScreen() {
    val repo = remember { Injekt.get<NovelRepository>() }
    val router = LocalRouter.currentOrThrow
    val backPress = LocalBackPress.current

    val novels by repo.getAllAsFlow().collectAsState(initial = emptyList())
    val favorites = remember(novels) { novels.filter { it.favorite }.sortedBy { it.title.lowercase() } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "LN library  •  ${favorites.size}",
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
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            if (favorites.isEmpty()) {
                Text("Library is empty. Save a novel from Debug → LN browse.")
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
                items(items = favorites, key = { it.id ?: 0L }) { novel ->
                    Column(
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
                    ) {
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
                    HorizontalDivider()
                }
            }
        }
    }
}
