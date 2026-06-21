package reikai.presentation.novel.notes

import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.components.AppBarTitle
import eu.kanade.presentation.manga.components.MangaNotesTextArea
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.flow.update
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.model.NovelUpdate
import tachiyomi.core.common.util.lang.launchNonCancellable
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Full-screen markdown notes editor for a novel, the twin of
 * [eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen]. Reuses Mihon's shared [MangaNotesTextArea]
 * (decoupled to take a plain String) and saves surgically via a [NovelUpdate] patch. Constructor args
 * are primitives so the Voyager screen stays serializable across state-save.
 */
class NovelNotesScreen(
    private val novelId: Long,
    private val novelTitle: String,
    private val initialNotes: String,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow

        val screenModel = rememberScreenModel { Model(novelId, initialNotes) }
        val state by screenModel.state.collectAsState()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    titleContent = {
                        AppBarTitle(
                            title = stringResource(MR.strings.action_edit_notes),
                            subtitle = novelTitle,
                        )
                    },
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            MangaNotesTextArea(
                notes = state.notes,
                onUpdate = screenModel::updateNotes,
                modifier = Modifier
                    .padding(contentPadding)
                    .consumeWindowInsets(contentPadding)
                    .imePadding(),
            )
        }
    }

    private class Model(
        private val novelId: Long,
        initialNotes: String,
        private val novelRepository: NovelRepository = Injekt.get(),
    ) : StateScreenModel<State>(State(initialNotes)) {

        fun updateNotes(content: String) {
            if (content == state.value.notes) return
            mutableState.update { it.copy(notes = content) }
            screenModelScope.launchNonCancellable {
                novelRepository.update(NovelUpdate(id = novelId, notes = content))
            }
        }
    }

    @Immutable
    data class State(
        val notes: String,
    )
}
