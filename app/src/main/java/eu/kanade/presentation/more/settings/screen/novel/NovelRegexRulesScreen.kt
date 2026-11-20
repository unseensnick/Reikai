package eu.kanade.presentation.more.settings.screen.novel

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.more.settings.screen.novel.components.NovelRegexRuleEditDialog
import eu.kanade.presentation.more.settings.screen.novel.components.NovelToggleList
import kotlinx.serialization.Serializable
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/**
 * The reader's find-and-replace rules. Novel-only by mechanism: a rule rewrites chapter text, and a
 * manga chapter is images the source ships, so there is nothing for one to act on.
 */
@Serializable
data object NovelRegexRulesRoute : NavKey

@Composable
fun NovelRegexRulesScreen() {
    val viewModel = metroViewModel<NovelRegexRulesViewModel>()
    NovelToggleList(
        viewModel = viewModel,
        title = stringResource(MR.strings.pref_novel_regex_rules),
        emptyRes = MR.strings.information_empty_novel_regex_rules,
        deleteConfirmationRes = MR.strings.novel_regex_delete_confirmation,
        // What the rule does, because a name alone cannot be checked against what a chapter comes
        // out looking like.
        summary = { rule ->
            stringResource(
                MR.strings.novel_regex_rule_summary,
                rule.pattern,
                rule.replacement.ifEmpty { stringResource(MR.strings.novel_regex_removes) },
            )
        },
        editDialog = { rule ->
            val preview by viewModel.preview.collectAsState()
            NovelRegexRuleEditDialog(
                rule = rule,
                preview = preview,
                onDismissRequest = viewModel::dismissDialog,
                onSave = viewModel::save,
                onPreview = viewModel::askPreview,
            )
        },
    )
}
