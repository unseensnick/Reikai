package reikai.presentation.browse.source

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
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
 * Every installed source is listed under its language heading with a checkbox; unchecking one disables
 * it (hidden from the Sources tab and global search). Novels have no per-language enable model, so the
 * headings are plain section labels, not language toggles.
 */
class NovelSourcesFilterScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { NovelSourcesFilterScreenModel() }
        val state by screenModel.state.collectAsState()

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
                NovelSourcesFilterScreenModel.State.Loading -> LoadingScreen(Modifier.padding(contentPadding))
                is NovelSourcesFilterScreenModel.State.Success -> {
                    if (s.isEmpty) {
                        EmptyScreen(stringRes = MR.strings.ln_no_sources, modifier = Modifier.padding(contentPadding))
                    } else {
                        FastScrollLazyColumn(contentPadding = contentPadding) {
                            s.items.forEach { (language, sources) ->
                                item(key = "ln-filter-header-$language", contentType = "header") {
                                    BrowseSectionHeader(
                                        title = LocaleHelper.getSourceDisplayName(language, LocalContext.current),
                                    )
                                }
                                items(
                                    items = sources,
                                    key = { "ln-filter-${it.id}" },
                                    contentType = { "item" },
                                ) { source ->
                                    NovelSourceRow(
                                        name = source.name,
                                        lang = "",
                                        iconUrl = source.iconUrl,
                                        onClickItem = { screenModel.toggleSource(source.id) },
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
