package reikai.presentation.novel.migrate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
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
import cafe.adriel.voyager.core.model.StateScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import kotlinx.coroutines.flow.update
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.MigrateNovelUseCase
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelMigrationFlag
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
 * The "what to include" dialog for a novel source migration, the novel twin of
 * [mihon.feature.migration.dialog.MigrateMangaDialog]. Lists the applicable [NovelMigrationFlag]s and
 * offers Copy (keep the old novel favorited too) vs Migrate (unfavorite the old). The target is
 * already materialised + chapter-synced by the caller.
 */
@Composable
fun Screen.MigrateNovelDialog(
    current: Novel,
    target: Novel,
    onDismissRequest: () -> Unit,
    onComplete: () -> Unit = onDismissRequest,
) {
    val scope = rememberCoroutineScope()
    val screenModel = rememberScreenModel { MigrateNovelDialogScreenModel() }
    LaunchedEffect(current, target) { screenModel.init(current, target) }
    val state by screenModel.state.collectAsState()

    if (state.isMigrated) return
    if (state.isMigrating) {
        LoadingScreen(modifier = Modifier.background(MaterialTheme.colorScheme.background.copy(alpha = 0.7f)))
        return
    }

    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(text = stringResource(MR.strings.migration_dialog_what_to_include)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                NovelMigrationFlag.ALL.forEach { flag ->
                    LabeledCheckbox(
                        label = stringResource(flag.titleRes),
                        checked = flag in state.selectedFlags,
                        onCheckedChange = { screenModel.toggle(flag) },
                    )
                }
            }
        },
        confirmButton = {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MaterialTheme.padding.extraSmall),
            ) {
                Spacer(modifier = Modifier.weight(1f))
                TextButton(
                    onClick = {
                        scope.launchIO {
                            screenModel.migrate(replace = false)
                            withUIContext { onComplete() }
                        }
                    },
                ) {
                    Text(text = stringResource(MR.strings.copy))
                }
                TextButton(
                    onClick = {
                        scope.launchIO {
                            screenModel.migrate(replace = true)
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
    private val migrateNovel: MigrateNovelUseCase = Injekt.get(),
) : StateScreenModel<MigrateNovelDialogScreenModel.State>(State()) {

    fun init(current: Novel, target: Novel) {
        mutableState.update {
            State(
                current = current,
                target = target,
                selectedFlags = NovelMigrationFlag.fromBits(novelPreferences.novelMigrationFlags().get()),
            )
        }
    }

    fun toggle(flag: NovelMigrationFlag) {
        mutableState.update {
            val selected = it.selectedFlags.toMutableSet()
                .apply { if (contains(flag)) remove(flag) else add(flag) }
                .toSet()
            it.copy(selectedFlags = selected)
        }
    }

    suspend fun migrate(replace: Boolean) {
        val current = state.value.current ?: return
        val target = state.value.target ?: return
        val flags = state.value.selectedFlags
        novelPreferences.novelMigrationFlags().set(NovelMigrationFlag.toBits(flags))
        mutableState.update { it.copy(isMigrating = true) }
        migrateNovel(current, target, flags, replace)
        mutableState.update { it.copy(isMigrating = false, isMigrated = true) }
    }

    data class State(
        val current: Novel? = null,
        val target: Novel? = null,
        val selectedFlags: Set<NovelMigrationFlag> = NovelMigrationFlag.ALL,
        val isMigrating: Boolean = false,
        val isMigrated: Boolean = false,
    )
}
