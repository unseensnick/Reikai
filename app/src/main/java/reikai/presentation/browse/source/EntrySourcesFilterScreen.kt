package reikai.presentation.browse.source

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.browse.components.BaseSourceItem
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.more.settings.widget.SwitchPreferenceWidget
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.browse.source.SourcesFilterViewModel
import eu.kanade.tachiyomi.util.system.LocaleHelper
import eu.kanade.tachiyomi.util.system.toast
import reikai.domain.library.ContentType
import reikai.presentation.browse.components.NovelSourceRow
import reikai.presentation.components.ContentTypeFilterChips
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.FastScrollLazyColumn
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.screens.EmptyScreen
import tachiyomi.presentation.core.screens.LoadingScreen

/**
 * Enable and disable sources, for both content types, with a Manga / Novels chip choosing which half
 * is showing. One screen rather than two because the Browse filter action has to reach both from any
 * chip: routing All and Manga to the manga screen left every plugin unreachable from the default
 * chip. The two halves keep their own preferences, so this shares the chrome and nothing else.
 */
class EntrySourcesFilterScreen(
    /** The Browse chip this was opened from. All lands on manga, the half it used to open alone. */
    private val initial: ContentType = ContentType.MANGA,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        // Held as a boolean rather than the enum: only two of the three chips exist here, and a
        // boolean is saveable without asking whether the enum is.
        var showNovels by rememberSaveable { mutableStateOf(initial == ContentType.NOVELS) }

        Scaffold(
            topBar = { scrollBehavior ->
                Column {
                    AppBar(
                        title = stringResource(MR.strings.label_sources),
                        navigateUp = navigator::pop,
                        scrollBehavior = scrollBehavior,
                    )
                    ContentTypeFilterChips(
                        selected = if (showNovels) ContentType.NOVELS else ContentType.MANGA,
                        onSelect = { showNovels = it == ContentType.NOVELS },
                        types = FILTER_TYPES,
                    )
                }
            },
        ) { contentPadding ->
            if (showNovels) {
                NovelSources(contentPadding)
            } else {
                MangaSources(contentPadding, navigator::pop)
            }
        }
    }

    @Composable
    private fun MangaSources(contentPadding: PaddingValues, navigateUp: () -> Unit) {
        val context = LocalContext.current
        val viewModel = metroViewModel<SourcesFilterViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        when (val s = state) {
            SourcesFilterViewModel.State.Loading -> LoadingScreen(Modifier.padding(contentPadding))
            // Upstream toasted and left the screen, and it still does. Leaving takes the novel half
            // with it, which is survivable: this half is only composed under the Manga chip, so
            // opening from Novels never touches the failing flow.
            is SourcesFilterViewModel.State.Error -> LaunchedEffect(Unit) {
                context.toast(MR.strings.internal_error)
                navigateUp()
            }
            is SourcesFilterViewModel.State.Success -> if (s.isEmpty) {
                EmptyScreen(
                    stringRes = MR.strings.source_filter_empty_screen,
                    modifier = Modifier.padding(contentPadding),
                )
            } else {
                FastScrollLazyColumn(contentPadding = contentPadding) {
                    s.items.forEach { (language, sources) ->
                        val enabled = language in s.enabledLanguages
                        item(key = language, contentType = "source-filter-header") {
                            SwitchPreferenceWidget(
                                modifier = Modifier.animateItem(),
                                title = LocaleHelper.getSourceDisplayName(language, context),
                                checked = enabled,
                                onCheckedChanged = { viewModel.toggleLanguage(language) },
                            )
                        }
                        if (enabled) {
                            items(
                                items = sources,
                                key = { "source-filter-${it.key()}" },
                                contentType = { "source-filter-item" },
                            ) { source ->
                                BaseSourceItem(
                                    modifier = Modifier.animateItem(),
                                    source = source,
                                    showLanguageInContent = false,
                                    onClickItem = { viewModel.toggleSource(source) },
                                    action = {
                                        Checkbox(
                                            checked = "${source.id}" !in s.disabledSources,
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

    @Composable
    private fun NovelSources(contentPadding: PaddingValues) {
        val context = LocalContext.current
        val viewModel = metroViewModel<NovelSourcesFilterViewModel>()
        val state by viewModel.state.collectAsStateWithLifecycle()

        when (val s = state) {
            NovelSourcesFilterViewModel.State.Loading -> LoadingScreen(Modifier.padding(contentPadding))
            is NovelSourcesFilterViewModel.State.Success -> if (s.isEmpty) {
                EmptyScreen(stringRes = MR.strings.ln_no_sources, modifier = Modifier.padding(contentPadding))
            } else {
                FastScrollLazyColumn(contentPadding = contentPadding) {
                    s.items.forEach { (language, sources) ->
                        val enabled = language !in s.disabledLanguages
                        item(key = "ln-filter-header-$language", contentType = "header") {
                            // Language switch, like manga's above: off hides the whole group's
                            // sources here and everywhere else.
                            SwitchPreferenceWidget(
                                modifier = Modifier.animateItem(),
                                title = LocaleHelper.getSourceDisplayName(language, context),
                                checked = enabled,
                                onCheckedChanged = { viewModel.toggleLanguage(language) },
                            )
                        }
                        if (enabled) {
                            items(
                                items = sources,
                                key = { "ln-filter-${it.id}" },
                                contentType = { "item" },
                            ) { source ->
                                NovelSourceRow(
                                    modifier = Modifier.animateItem(),
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

private val FILTER_TYPES = listOf(ContentType.MANGA, ContentType.NOVELS)
