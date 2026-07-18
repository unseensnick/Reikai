package reikai.presentation.manga

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
import mihon.feature.migration.config.MigrationConfigScreen
import reikai.domain.manga.MangaMergeManager
import reikai.presentation.migrate.MigrationSourcePickContent
import reikai.presentation.migrate.PickMember
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Pre-step for migrating a merged manga: pick which source(s) of the merge group to migrate, the
 * manga twin of [reikai.presentation.novel.migrate.NovelMigrationSourcePickScreen]. Continue advances
 * to Mihon's [MigrationConfigScreen] with the chosen ids; when nothing in the selection is merged it
 * forwards straight there. The UI is the shared [MigrationSourcePickContent].
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
        MigrationSourcePickContent(
            members = state.members,
            checked = state.checked,
            onToggle = screenModel::toggle,
            onContinue = { navigator.replace(MigrationConfigScreen(state.checked.toList())) },
            navigateUp = navigator::pop,
        )
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
                mangaMergeManager.computeRelatedMangaIds(manga.id).forEach { memberIds += it }
            }
            if (memberIds == LinkedHashSet(mangaIds)) {
                mutableState.update { it.copy(loading = false, skip = true) }
                return@launchIO
            }
            val members = memberIds.mapNotNull { id ->
                getManga.await(id)?.let { manga ->
                    PickMember(
                        id = manga.id,
                        title = manga.title,
                        coverData = manga,
                        subtitle = listOfNotNull(
                            sourceManager.get(manga.source)?.name,
                            "${getChaptersByMangaId.await(manga.id).size} ch",
                        ).joinToString(" · "),
                    )
                }
            }
            mutableState.update { it.copy(loading = false, members = members, checked = mangaIds.toSet()) }
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
