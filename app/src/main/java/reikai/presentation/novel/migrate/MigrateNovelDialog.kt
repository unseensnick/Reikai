package reikai.presentation.novel.migrate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.util.fastForEach
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.data.cache.CoverCache
import kotlinx.coroutines.flow.update
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.MigrateNovelUseCase
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelMigrationFlag
import reikai.domain.novel.model.hasCustomCover
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.core.common.util.lang.withUIContext
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.LabeledCheckbox
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.LoadingScreen
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * One-tap in-place migrate confirmation for a novel, the novel twin of Mihon's
 * [mihon.feature.migration.dialog.MigrateMangaDialog]. Reached from the duplicate dialog: the target is
 * already the novel being added (materialised + chapter-synced by the details screen), so this only picks
 * what to carry over and runs [MigrateNovelUseCase] on Copy / Migrate. Distinct from the source-picker
 * migration screen (that is the toolbar's batch workflow).
 */
@Composable
internal fun Screen.MigrateNovelDialog(
    current: Novel,
    target: Novel,
    onClickTitle: () -> Unit,
    onDismissRequest: () -> Unit,
    onComplete: () -> Unit = onDismissRequest,
) {
    val scope = rememberCoroutineScope()
    val screenModel = rememberScreenModel { MigrateNovelDialogScreenModel() }
    LaunchedEffect(current, target) {
        screenModel.init(current, target)
    }
    val state by screenModel.state.collectAsState()

    if (state.isMigrated) return

    if (state.isMigrating) {
        LoadingScreen(
            modifier = Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)),
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(MR.strings.migration_dialog_what_to_include))
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                state.applicableFlags.fastForEach { flag ->
                    LabeledCheckbox(
                        label = stringResource(flag.titleRes),
                        checked = flag in state.selectedFlags,
                        onCheckedChange = { screenModel.toggleSelection(flag) },
                    )
                }
            }
        },
        confirmButton = {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                TextButton(
                    onClick = {
                        onDismissRequest()
                        onClickTitle()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_show_manga))
                }

                Spacer(modifier = Modifier.weight(1f))

                TextButton(
                    onClick = {
                        scope.launchIO {
                            screenModel.migrateNovel(replace = false)
                            withUIContext { onComplete() }
                        }
                    },
                ) {
                    Text(text = stringResource(MR.strings.copy))
                }
                TextButton(
                    onClick = {
                        scope.launchIO {
                            screenModel.migrateNovel(replace = true)
                            withUIContext { onComplete() }
                        }
                    },
                ) {
                    Text(text = stringResource(MR.strings.migrate))
                }
            }
        },
    )
}

private class MigrateNovelDialogScreenModel(
    private val novelPreferences: NovelPreferences = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val migrateNovel: MigrateNovelUseCase = Injekt.get(),
) : StateScreenModel<MigrateNovelDialogScreenModel.State>(State()) {

    fun init(current: Novel, target: Novel) {
        // A flag is offered only when it has something to carry (mirrors the batch confirm dialog):
        // cover only with a custom cover, notes only when non-blank; the rest always apply.
        val applicableFlags = NovelMigrationFlag.entries.filter { flag ->
            when (flag) {
                NovelMigrationFlag.COVER -> current.hasCustomCover(coverCache)
                NovelMigrationFlag.NOTES -> current.notes.isNotBlank()
                else -> true
            }
        }
        val selectedFlags = NovelMigrationFlag.fromBits(novelPreferences.novelMigrationFlags().get())
        mutableState.update {
            State(
                current = current,
                target = target,
                applicableFlags = applicableFlags,
                selectedFlags = selectedFlags,
            )
        }
    }

    fun toggleSelection(flag: NovelMigrationFlag) {
        mutableState.update {
            val selectedFlags = it.selectedFlags.toMutableSet()
                .apply { if (contains(flag)) remove(flag) else add(flag) }
                .toSet()
            it.copy(selectedFlags = selectedFlags)
        }
    }

    suspend fun migrateNovel(replace: Boolean) {
        val state = state.value
        val current = state.current ?: return
        val target = state.target ?: return
        novelPreferences.novelMigrationFlags().set(NovelMigrationFlag.toBits(state.selectedFlags))
        mutableState.update { it.copy(isMigrating = true) }
        migrateNovel(current, target, state.selectedFlags, replace)
        mutableState.update { it.copy(isMigrating = false, isMigrated = true) }
    }

    data class State(
        val current: Novel? = null,
        val target: Novel? = null,
        val applicableFlags: List<NovelMigrationFlag> = emptyList(),
        val selectedFlags: Set<NovelMigrationFlag> = emptySet(),
        val isMigrating: Boolean = false,
        val isMigrated: Boolean = false,
    )
}
