package yokai.presentation.details.manga

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import eu.kanade.tachiyomi.ui.reader.ReaderActivity
import eu.kanade.tachiyomi.util.compose.LocalBackPress
import eu.kanade.tachiyomi.util.mapStatus
import yokai.domain.manga.models.cover
import yokai.presentation.component.ReikaiTopBar
import yokai.presentation.details.DetailsChapterRow
import yokai.presentation.details.DetailsContent
import yokai.util.Screen

class MangaDetailsScreen(private val mangaId: Long) : Screen() {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {
        val screenModel = rememberScreenModel { MangaDetailsScreenModel(mangaId) }
        val state by screenModel.state.collectAsState()
        val backPress = LocalBackPress.current
        val context = LocalContext.current

        val loaded = state as? MangaDetailsState.Loaded

        Scaffold(
            topBar = {
                ReikaiTopBar(
                    title = { Text(loaded?.manga?.title.orEmpty(), style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = { backPress?.invoke() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                        }
                    },
                )
            },
            floatingActionButton = {
                val resume = loaded?.resumeChapter
                if (loaded != null && resume != null) {
                    ExtendedFloatingActionButton(
                        text = { Text(if (loaded.hasStarted) "Resume" else "Start reading") },
                        icon = { Icon(Icons.Filled.PlayArrow, contentDescription = null) },
                        onClick = { context.startActivity(ReaderActivity.newIntent(context, loaded.manga, resume)) },
                    )
                }
            },
        ) { padding ->
            when (val s = state) {
                is MangaDetailsState.Loading -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is MangaDetailsState.NotFound -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { Text("Manga not found", color = MaterialTheme.colorScheme.error) }

                is MangaDetailsState.Loaded -> {
                    val manga = s.manga
                    val coverData = remember(manga.id) { manga.cover() }
                    val genres = remember(manga.id, manga.genre) {
                        manga.genre?.split(",")?.mapNotNull { it.trim().ifBlank { null } }.orEmpty()
                    }
                    val rows = remember(s.chapters) {
                        s.chapters.map {
                            DetailsChapterRow(
                                id = it.id ?: 0L,
                                name = it.name,
                                read = it.read,
                                bookmark = it.bookmark,
                            )
                        }
                    }
                    DetailsContent(
                        coverData = coverData,
                        title = manga.title,
                        author = manga.author,
                        statusText = context.mapStatus(manga.status),
                        description = manga.description,
                        genres = genres,
                        chapters = rows,
                        onChapterClick = { id ->
                            s.chapters.find { it.id == id }?.let { chapter ->
                                context.startActivity(ReaderActivity.newIntent(context, manga, chapter))
                            }
                        },
                        onToggleRead = { id, read -> screenModel.setRead(id, read) },
                        onToggleBookmark = { id, bookmark -> screenModel.setBookmark(id, bookmark) },
                        modifier = Modifier.padding(padding),
                    )
                }
            }
        }
    }
}
