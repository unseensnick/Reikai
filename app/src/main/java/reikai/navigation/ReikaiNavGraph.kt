package reikai.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.more.settings.screen.SettingsEhScreen
import eu.kanade.presentation.more.settings.screen.SettingsMangaDexScreen
import eu.kanade.presentation.more.settings.screen.SettingsMangaReaderScreen
import eu.kanade.presentation.more.settings.screen.SettingsNovelReaderScreen
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.more.settings.screen.novel.NovelCodeSnippetsRoute
import eu.kanade.presentation.more.settings.screen.novel.NovelCodeSnippetsScreen
import eu.kanade.presentation.more.settings.screen.novel.NovelFontsRoute
import eu.kanade.presentation.more.settings.screen.novel.NovelFontsScreen
import eu.kanade.presentation.more.settings.screen.novel.NovelRegexRulesRoute
import eu.kanade.presentation.more.settings.screen.novel.NovelRegexRulesScreen
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import exh.debug.SettingsDebugRoute
import exh.debug.SettingsDebugScreen
import exh.md.follows.MangaDexFollowsRoute
import exh.md.follows.MangaDexFollowsScreen
import exh.pagepreview.PagePreviewRoute
import exh.pagepreview.PagePreviewScreen
import exh.ui.batchadd.BatchAddRoute
import exh.ui.batchadd.BatchAddScreen
import exh.ui.metadata.MetadataViewRoute
import exh.ui.metadata.MetadataViewScreen
import mihon.core.navigation.LocalAssistContentManager
import mihon.core.navigation.LocalNavigator
import mihon.core.navigation.OverlaySheetScene
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueRoute
import reikai.presentation.browse.catalogue.EntryCatalogueScreen
import reikai.presentation.browse.extension.details.NovelPluginDetailsRoute
import reikai.presentation.browse.extension.details.NovelPluginDetailsScreen
import reikai.presentation.browse.globalsearch.EntryGlobalSearchRoute
import reikai.presentation.browse.globalsearch.EntryGlobalSearchScreen
import reikai.presentation.browse.source.EntrySourcesFilterRoute
import reikai.presentation.browse.source.EntrySourcesFilterScreen
import reikai.presentation.browse.source.NovelSourcePreferencesRoute
import reikai.presentation.library.hidden.HiddenEntriesRoute
import reikai.presentation.library.hidden.HiddenEntriesScreen
import reikai.presentation.library.preferredsources.PreferredSourcesRoute
import reikai.presentation.library.preferredsources.PreferredSourcesScreen
import reikai.presentation.library.updateerror.UpdateErrorsRoute
import reikai.presentation.library.updateerror.UpdateErrorsScreen
import reikai.presentation.local.LocalImportRoute
import reikai.presentation.local.LocalImportScreen
import reikai.presentation.migrate.flow.EntryMigrationConfigRoute
import reikai.presentation.migrate.flow.EntryMigrationConfigScreen
import reikai.presentation.migrate.flow.EntryMigrationFavoritesRoute
import reikai.presentation.migrate.flow.EntryMigrationFavoritesScreen
import reikai.presentation.migrate.flow.EntryMigrationListRoute
import reikai.presentation.migrate.flow.EntryMigrationListScreen
import reikai.presentation.migrate.flow.EntryMigrationSearchRoute
import reikai.presentation.migrate.flow.EntryMigrationSearchScreen
import reikai.presentation.migrate.flow.EntryMigrationSourcePickRoute
import reikai.presentation.migrate.flow.EntryMigrationSourcePickScreen
import reikai.presentation.notes.VolumeNotesRoute
import reikai.presentation.notes.VolumeNotesScreen
import reikai.presentation.novel.details.NovelRoute
import reikai.presentation.novel.details.NovelScreen
import reikai.presentation.novel.notes.NovelNotesRoute
import reikai.presentation.novel.notes.NovelNotesScreen
import reikai.presentation.novel.search.NovelChapterSearchRoute
import reikai.presentation.novel.search.NovelChapterSearchScreen
import reikai.presentation.reader.ReaderBottomButtonsRoute
import reikai.presentation.reader.ReaderBottomButtonsScreen
import reikai.presentation.recommendation.SettingsRecommendationsScreen
import reikai.presentation.recommendation.browse.RelatedMangasBrowseRoute
import reikai.presentation.recommendation.browse.RelatedMangasBrowseScreen
import reikai.presentation.settings.appearance.CustomThemeColorRoute
import reikai.presentation.settings.appearance.CustomThemeColorScreen
import reikai.presentation.settings.appearance.LauncherIconRoute
import reikai.presentation.settings.appearance.LauncherIconScreen
import reikai.presentation.settings.download.SourceDelaysRoute
import reikai.presentation.settings.download.SourceDelaysScreen
import reikai.presentation.stats.details.StatsGroupRoute
import reikai.presentation.stats.details.StatsGroupScreen
import reikai.presentation.stats.details.StatsMostReadRoute
import reikai.presentation.stats.details.StatsMostReadScreen
import reikai.presentation.stats.details.StatsReadingRoute
import reikai.presentation.stats.details.StatsReadingScreen
import reikai.presentation.stats.details.StatsSeriesRoute
import reikai.presentation.stats.details.StatsSeriesScreen
import reikai.presentation.track.EntryTrackSheet
import reikai.presentation.track.EntryTrackSheetRoute

/**
 * Every page Reikai adds, registered after upstream's [mihon.core.navigation.appEntries] in the one place a
 * route meets its screen. A route registered nowhere crashes when pushed, and ReikaiRoutesTest fails on
 * one missing from its list, so a new route is added here and there together.
 */
fun EntryProviderScope<NavKey>.reikaiEntries() {
    entry<NovelRoute> { route ->
        LocalAssistContentManager.current.Provide(route) {
            NovelScreen(route.novelId, route.fromSource)
        }
    }
    entry<EntryCatalogueRoute> { route ->
        LocalAssistContentManager.current.Provide(route) {
            EntryCatalogueScreen(
                route.sourceKey,
                route.initialQuery,
                route.startLatest,
                route.migrateFor,
                route.savedSearchId,
            )
        }
    }
    entry<EntryTrackSheetRoute>(metadata = OverlaySheetScene.overlaySheet()) { route ->
        val navigator = LocalNavigator.current
        EntryTrackSheet(route.entry, route.entryTitle, onDismissRequest = { navigator.pop() })
    }
    entry<NovelSourcePreferencesRoute> { SourcePreferencesScreen(SourceKey.Novel(it.sourceId)) }

    entry<AboutScreen> { AboutScreen.Content() }
    entry<SettingsEhScreen> { SettingsEhScreen.Content() }
    entry<SettingsMangaDexScreen> { SettingsMangaDexScreen.Content() }
    entry<SettingsMangaReaderScreen> { SettingsMangaReaderScreen.Content() }
    entry<SettingsNovelReaderScreen> { SettingsNovelReaderScreen.Content() }
    entry<SettingsRecommendationsScreen> { SettingsRecommendationsScreen.Content() }

    entry<BatchAddRoute> { BatchAddScreen() }
    entry<CustomThemeColorRoute> { CustomThemeColorScreen() }
    entry<EntryGlobalSearchRoute> {
        EntryGlobalSearchScreen(it.searchQuery, it.extensionFilter, it.scopedContentType, it.sourceFilter)
    }
    entry<EntryMigrationConfigRoute> { EntryMigrationConfigScreen(it.contentType, it.entryIds) }
    entry<EntryMigrationFavoritesRoute> { EntryMigrationFavoritesScreen(it.contentType, it.sourceKey) }
    entry<EntryMigrationListRoute> { EntryMigrationListScreen(it.contentType, it.entryIds, it.extraQuery, it.flowId) }
    entry<EntryMigrationSearchRoute> {
        EntryMigrationSearchScreen(it.contentType, it.entryId, it.extraQuery, it.flowId)
    }
    entry<EntryMigrationSourcePickRoute> { EntryMigrationSourcePickScreen(it.contentType, it.entryIds) }
    entry<EntrySourcesFilterRoute> { EntrySourcesFilterScreen(it.initial) }
    entry<HiddenEntriesRoute> { HiddenEntriesScreen(it.initialContentType) }
    entry<LauncherIconRoute> { LauncherIconScreen() }
    entry<LocalImportRoute> { LocalImportScreen(it.initialUris) }
    entry<MangaDexFollowsRoute> { MangaDexFollowsScreen(it.sourceId) }
    entry<MetadataViewRoute> { MetadataViewScreen(it.mangaId, it.sourceId) }
    entry<NovelChapterSearchRoute> { NovelChapterSearchScreen(it.novelId, it.sourceScoped) }
    entry<NovelCodeSnippetsRoute> { NovelCodeSnippetsScreen(it.kind) }
    entry<NovelFontsRoute> { NovelFontsScreen() }
    entry<NovelNotesRoute> { NovelNotesScreen(it.novelId) }
    entry<NovelPluginDetailsRoute> { NovelPluginDetailsScreen(it.pluginId) }
    entry<NovelRegexRulesRoute> { NovelRegexRulesScreen() }
    entry<PagePreviewRoute> { PagePreviewScreen(it.mangaId) }
    entry<PreferredSourcesRoute> { PreferredSourcesScreen() }
    entry<ReaderBottomButtonsRoute> { ReaderBottomButtonsScreen(it.scope) }
    entry<RelatedMangasBrowseRoute> { RelatedMangasBrowseScreen(it.mangaId) }
    entry<SettingsDebugRoute> { SettingsDebugScreen() }
    entry<SourceDelaysRoute> { SourceDelaysScreen() }
    entry<StatsGroupRoute> { StatsGroupScreen(it.dimension) }
    entry<StatsMostReadRoute> { StatsMostReadScreen() }
    entry<StatsReadingRoute> { StatsReadingScreen() }
    entry<StatsSeriesRoute> { StatsSeriesScreen(it.groupKey, it.name, it.contentType) }
    entry<UpdateErrorsRoute> { UpdateErrorsScreen(it.initialContentType) }
    entry<VolumeNotesRoute> { VolumeNotesScreen(it.type, it.series, it.volumeKey) }
}
