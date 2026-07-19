package reikai.presentation.novel.migrate

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.update
import reikai.data.coil.NovelCover
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.novel.source.NovelSourceManager
import reikai.presentation.migrate.MigrationSourcePickContent
import reikai.presentation.migrate.PickMember
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Pre-step for migrating a merged novel: pick which source(s) of the merge group to migrate. Each
 * selected entry is expanded to its full merge group and the members are listed (cover, title,
 * source, chapter count), the originally-selected ids pre-checked. Continue advances to
 * [NovelMigrationConfigScreen] with the chosen ids; when nothing in the selection is merged it
 * forwards straight there. The UI is the shared [MigrationSourcePickContent].
 */
class NovelMigrationSourcePickScreen(
    private val novelIds: List<Long>,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelMigrationSourcePickScreenModel(novelIds) }
        val state by screenModel.state.collectAsState()

        LaunchedEffect(state.skip) {
            if (state.skip) navigator.replace(NovelMigrationConfigScreen(novelIds))
        }
        if (state.loading || state.skip) {
            LoadingScreen()
            return
        }
        MigrationSourcePickContent(
            members = state.members,
            checked = state.checked,
            onToggle = screenModel::toggle,
            onContinue = { navigator.replace(NovelMigrationConfigScreen(state.checked.toList())) },
            navigateUp = navigator::pop,
        )
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
                mergeManager.computeRelatedIds(novel.id).forEach { memberIds += it }
            }
            // No merged entries: the members are exactly the selection, so there's nothing to choose.
            if (memberIds == LinkedHashSet(novelIds)) {
                mutableState.update { it.copy(loading = false, skip = true) }
                return@launchIO
            }
            val members = memberIds.mapNotNull { id ->
                novelRepository.getById(id)?.let { novel ->
                    val source = sourceManager.get(novel.source)
                    PickMember(
                        id = novel.id,
                        title = novel.title,
                        coverData = NovelCover(
                            url = novel.thumbnailUrl,
                            site = source?.site,
                            isNovelFavorite = false,
                            lastModified = novel.coverLastModified,
                            novelId = novel.id,
                        ),
                        subtitle = listOfNotNull(source?.name, "${chapterRepository.getByNovelId(novel.id).size} ch")
                            .joinToString(" · "),
                    )
                }
            }
            mutableState.update { it.copy(loading = false, members = members, checked = novelIds.toSet()) }
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
        val members: List<PickMember> = emptyList(),
        val checked: Set<Long> = emptySet(),
    )
}
