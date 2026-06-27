package reikai.presentation.novel.migrate

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
import reikai.data.coil.NovelCover
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.Novel
import reikai.novel.source.NovelSourceManager
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Pre-step for migrating a merged novel: pick which source(s) of the merge group to migrate. Each
 * selected entry is expanded to its full merge group and the members are listed (cover, title,
 * source, chapter count), the originally-selected ids pre-checked. Continue advances to
 * [NovelMigrationConfigScreen] with the chosen ids. When nothing in the selection is merged there is
 * no choice to make, so the screen forwards straight to the config screen.
 */
class NovelMigrationSourcePickScreen(
    private val novelIds: List<Long>,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelMigrationSourcePickScreenModel(novelIds) }
        val state by screenModel.state.collectAsState()

        // No merged entries in the selection: nothing to pick, go straight to the source config.
        LaunchedEffect(state.skip) {
            if (state.skip) navigator.replace(NovelMigrationConfigScreen(novelIds))
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
                        onClick = { navigator.replace(NovelMigrationConfigScreen(state.checked.toList())) },
                    )
                }
            },
        ) { contentPadding ->
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = contentPadding) {
                items(items = state.members, key = { it.novel.id }) { member ->
                    MemberRow(
                        member = member,
                        checked = member.novel.id in state.checked,
                        onToggle = { screenModel.toggle(member.novel.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MemberRow(
    member: NovelMigrationSourcePickScreenModel.Member,
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
        MangaCover.Book(
            data = NovelCover(
                url = member.novel.thumbnailUrl,
                site = member.site,
                isNovelFavorite = false,
                lastModified = member.novel.coverLastModified,
                novelId = member.novel.id,
            ),
            modifier = Modifier.width(48.dp).padding(start = 4.dp),
        )
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = member.novel.title,
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

class NovelMigrationSourcePickScreenModel(
    private val novelIds: List<Long>,
    private val novelRepository: NovelRepository = Injekt.get(),
    private val chapterRepository: NovelChapterRepository = Injekt.get(),
    private val sourceManager: NovelSourceManager = Injekt.get(),
    private val mergeManager: NovelMergeManager = Injekt.get(),
) : StateScreenModel<NovelMigrationSourcePickScreenModel.State>(State()) {

    init {
        screenModelScope.launchIO {
            // Expand every selected novel to its full merge group, deduped in encounter order.
            val memberIds = LinkedHashSet<Long>()
            novelIds.forEach { id ->
                val novel = novelRepository.getById(id) ?: return@forEach
                mergeManager.computeRelatedNovelIds(novel.id, novel.title, novel.author).forEach { memberIds += it }
            }
            // No merged entries: the members are exactly the selection, so there's nothing to choose.
            if (memberIds == novelIds.toLinkedSet()) {
                mutableState.update { it.copy(loading = false, skip = true) }
                return@launchIO
            }
            val members = memberIds.mapNotNull { id ->
                novelRepository.getById(id)?.let { novel ->
                    Member(
                        novel = novel,
                        site = sourceManager.get(novel.source)?.site,
                        sourceName = sourceManager.get(novel.source)?.name,
                        chapterCount = chapterRepository.getByNovelId(novel.id).size,
                    )
                }
            }
            mutableState.update {
                it.copy(loading = false, members = members, checked = novelIds.toSet())
            }
        }
    }

    fun toggle(id: Long) {
        mutableState.update {
            it.copy(checked = if (id in it.checked) it.checked - id else it.checked + id)
        }
    }

    private fun List<Long>.toLinkedSet(): LinkedHashSet<Long> = LinkedHashSet(this)

    data class State(
        val loading: Boolean = true,
        val skip: Boolean = false,
        val members: List<Member> = emptyList(),
        val checked: Set<Long> = emptySet(),
    )

    data class Member(
        val novel: Novel,
        val site: String?,
        val sourceName: String?,
        val chapterCount: Int,
    )
}
