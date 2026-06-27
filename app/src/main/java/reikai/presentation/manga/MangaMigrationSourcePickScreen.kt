package reikai.presentation.manga

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallExtendedFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.update
import mihon.feature.migration.config.MigrationConfigScreen
import reikai.domain.manga.MangaMergeManager
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Pre-step for migrating a merged manga: pick which source(s) of the merge group to migrate, the
 * manga twin of [reikai.presentation.novel.migrate.NovelMigrationSourcePickScreen]. Each selected
 * manga is expanded to its full merge group and the members are listed (cover, title, source, chapter
 * count), the originally-selected ids pre-checked. Continue advances to Mihon's [MigrationConfigScreen]
 * with the chosen ids. When nothing in the selection is merged it forwards straight to that screen.
 */
class MangaMigrationSourcePickScreen(
    private val mangaIds: List<Long>,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { MangaMigrationSourcePickScreenModel(mangaIds) }
        val state by screenModel.state.collectAsState()

        LaunchedEffect(state.skip) {
            if (state.skip) navigator.replace(MigrationConfigScreen(mangaIds))
        }
        if (state.loading || state.skip) {
            LoadingScreen()
            return
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.action_migrate),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
            floatingActionButton = {
                if (state.checked.isNotEmpty()) {
                    SmallExtendedFloatingActionButton(
                        text = { Text(text = stringResource(MR.strings.migrationConfigScreen_continueButtonText)) },
                        icon = { Icon(imageVector = Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null) },
                        onClick = { navigator.replace(MigrationConfigScreen(state.checked.toList())) },
                    )
                }
            },
        ) { contentPadding ->
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                items(items = state.members, key = { it.manga.id }) { member ->
                    MemberRow(
                        member = member,
                        checked = member.manga.id in state.checked,
                        onToggle = { screenModel.toggle(member.manga.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: MangaMigrationSourcePickScreenModel.Member,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = MaterialTheme.padding.medium, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        MangaCover.Book(data = member.manga, modifier = Modifier.width(48.dp).padding(start = 4.dp))
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = member.manga.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(member.sourceName, "${member.chapterCount} ch").joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

class MangaMigrationSourcePickScreenModel(
    private val mangaIds: List<Long>,
    private val getManga: GetManga = Injekt.get(),
    private val getChaptersByMangaId: GetChaptersByMangaId = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val mangaMergeManager: MangaMergeManager = Injekt.get(),
) : StateScreenModel<MangaMigrationSourcePickScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            val memberIds = LinkedHashSet<Long>()
            mangaIds.forEach { id ->
                val manga = getManga.await(id) ?: return@forEach
                mangaMergeManager.computeRelatedMangaIds(manga.id, manga.title).ids.forEach { memberIds += it }
            }
            if (memberIds == LinkedHashSet(mangaIds)) {
                mutableState.update { it.copy(loading = false, skip = true) }
                return@launchIO
            }
            val members = memberIds.mapNotNull { id ->
                getManga.await(id)?.let { manga ->
                    Member(
                        manga = manga,
                        sourceName = sourceManager.get(manga.source)?.name,
                        chapterCount = getChaptersByMangaId.await(manga.id).size,
                    )
                }
            }
            mutableState.update {
                it.copy(loading = false, members = members, checked = mangaIds.toSet())
            }
        }
    }

    fun toggle(id: Long) {
        mutableState.update {
            it.copy(checked = if (id in it.checked) it.checked - id else it.checked + id)
        }
    }

    data class State(
        val loading: Boolean = true,
        val skip: Boolean = false,
        val members: List<Member> = emptyList(),
        val checked: Set<Long> = emptySet(),
    )

    data class Member(
        val manga: Manga,
        val sourceName: String?,
        val chapterCount: Int,
    )
}
