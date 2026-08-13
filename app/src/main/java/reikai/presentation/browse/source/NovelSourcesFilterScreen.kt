package reikai.presentation.browse.source

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.LocaleHelper
import reikai.presentation.browse.components.BrowseSectionHeader
import reikai.presentation.browse.components.NovelSourceRow
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Bulk enable/disable screen for light-novel sources, the novel twin of Mihon's manga sources filter.
 * Each language heading is a switch (disabling it hides every source of that language, like manga);
 * under an enabled language every installed source is listed with a checkbox, and unchecking one
 * disables just that source. Both hide from the Sources tab and global search.
 */
class NovelSourcesFilterScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = viewModel<NovelSourcesFilterViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        Scaffold(
            topBar = { scrollBehavior ->
                AppBar(
                    title = stringResource(MR.strings.label_sources),
                    navigateUp = navigator::pop,
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { contentPadding ->
            when (val s = state) {
                NovelSourcesFilterViewModel.State.Loading -> LoadingScreen(Modifier.padding(contentPadding))
                is NovelSourcesFilterViewModel.State.Success -> {
                    if (s.isEmpty) {
                        EmptyScreen(stringRes = MR.strings.ln_no_sources, modifier = Modifier.padding(contentPadding))
                    } else {
                        FastScrollLazyColumn(contentPadding = contentPadding) {
                            s.items.forEach { (language, sources) ->
                                val languageEnabled = language !in s.disabledLanguages
                                item(key = "ln-filter-header-$language", contentType = "header") {
                                    // Language switch, like manga's SourcesFilterHeader: off hides
                                    // the whole group's sources here and everywhere else.
                                    SwitchPreferenceWidget(
                                        title = LocaleHelper.getSourceDisplayName(language, LocalContext.current),
                                        checked = languageEnabled,
                                        onCheckedChanged = { viewModel.toggleLanguage(language) },
                                    )
                                }
                                if (languageEnabled) {
                                    items(
                                        items = sources,
                                        key = { "ln-filter-${it.id}" },
                                        contentType = { "item" },
                                    ) { source ->
                                        NovelSourceRow(
                                            name = source.name,
                                            lang = "",
                                            iconUrl = source.iconUrl,
                                            onClickItem = { viewModel.toggleSource(source.id) },
                                            action = {
                                                Checkbox(
                                                    checked = source.id !in s.disabledSources,
                                                    onCheckedChange = null,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
