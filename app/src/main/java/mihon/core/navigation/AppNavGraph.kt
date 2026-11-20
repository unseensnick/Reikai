package mihon.core.navigation

import androidx.navigation3.runtime.EntryProviderScope
import androidx.navigation3.runtime.NavKey
import eu.kanade.presentation.more.settings.screen.SettingsAdvancedScreen
import eu.kanade.presentation.more.settings.screen.SettingsAppearanceScreen
import eu.kanade.presentation.more.settings.screen.SettingsBrowseScreen
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.screen.SettingsDownloadScreen
import eu.kanade.presentation.more.settings.screen.SettingsLibraryScreen
import eu.kanade.presentation.more.settings.screen.SettingsMainScreen
import eu.kanade.presentation.more.settings.screen.SettingsSearchScreen
import eu.kanade.presentation.more.settings.screen.SettingsSecurityScreen
import eu.kanade.presentation.more.settings.screen.SettingsTrackingScreen
import eu.kanade.presentation.more.settings.screen.about.OpenSourceLicensesScreen
import eu.kanade.presentation.more.settings.screen.advanced.ClearDatabaseScreen
import eu.kanade.presentation.more.settings.screen.appearance.AppLanguageScreen
import eu.kanade.presentation.more.settings.screen.data.CreateBackupScreen
import eu.kanade.presentation.more.settings.screen.data.RestoreBackupScreen
import eu.kanade.presentation.more.settings.screen.debug.BackupSchemaScreen
import eu.kanade.presentation.more.settings.screen.debug.DebugInfoScreen
import eu.kanade.presentation.more.settings.screen.debug.WorkerInfoScreen
import eu.kanade.tachiyomi.ui.browse.extension.ExtensionFilterScreen
import eu.kanade.tachiyomi.ui.browse.extension.details.ExtensionDetailsScreen
import eu.kanade.tachiyomi.ui.browse.extension.details.SourcePreferencesScreen
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.deeplink.DeepLinkScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import eu.kanade.tachiyomi.ui.manga.notes.MangaNotesScreen
import eu.kanade.tachiyomi.ui.more.NewUpdateScreen
import eu.kanade.tachiyomi.ui.more.OnboardingScreen
import eu.kanade.tachiyomi.ui.stats.StatsScreen
import eu.kanade.tachiyomi.ui.webview.WebViewScreen
import mihon.feature.upcoming.UpcomingScreen
import reikai.presentation.browse.repos.RepositoriesScreen

fun EntryProviderScope<NavKey>.appEntries() {
    entry<HomeRoute> {
        HomeScreen()
    }
    entry<DeepLinkRoute> { route ->
        DeepLinkScreen(route.query)
    }
    entry<WebViewRoute> { route ->
        val navigator = LocalNavigator.current
        LocalAssistContentManager.current.Provide(route) {
            WebViewScreen(route, onNavigateUp = navigator::pop)
        }
    }

    entry<MangaNotesRoute> { route ->
        MangaNotesScreen(route.mangaId)
    }
    entry<MangaRoute> { route ->
        LocalAssistContentManager.current.Provide(route) {
            MangaScreen(route.mangaId, route.fromSource)
        }
    }

    entry<UpcomingRoute> {
        UpcomingScreen()
    }

    entry<ExtensionDetailsRoute> { route ->
        ExtensionDetailsScreen(route.pkgName)
    }
    entry<ExtensionFilterRoute> {
        ExtensionFilterScreen()
    }
    entry<SourcePreferencesRoute> { route ->
        SourcePreferencesScreen(route.sourceId)
    }

    entry<CategoryRoute> {
        CategoryScreen()
    }
    entry<DownloadQueueRoute> {
        DownloadQueueScreen()
    }
    entry<NewUpdateRoute>(metadata = TwoPaneSettingsScene.fullScreen()) { route ->
        NewUpdateScreen(
            versionName = route.versionName,
            changelogInfo = route.changelogInfo,
            releaseLink = route.releaseLink,
            downloadLink = route.downloadLink,
        )
    }
    entry<OnboardingRoute> {
        OnboardingScreen()
    }
    entry<SettingsMainScreen>(metadata = TwoPaneSettingsScene.listPane()) {
        SettingsMainScreen.Content()
    }
    entry<StatsRoute> {
        StatsScreen()
    }

    // RK: About is registered as AboutScreen in reikaiEntries, the searchable settings page that replaces AboutRoute
    entry<AppLanguageRoute> {
        AppLanguageScreen()
    }
    entry<BackupSchemaRoute> {
        BackupSchemaScreen()
    }
    entry<ClearDatabaseRoute> {
        ClearDatabaseScreen()
    }
    entry<CreateBackupRoute> {
        CreateBackupScreen()
    }
    entry<DebugInfoRoute> {
        DebugInfoScreen()
    }
    entry<ExtensionStoresRoute> {
        RepositoriesScreen() // RK: one screen for extension stores and plugin repos
    }
    entry<OpenSourceLicensesRoute> {
        OpenSourceLicensesScreen()
    }
    entry<RestoreBackupRoute> { route ->
        RestoreBackupScreen(route.uri)
    }
    entry<SettingsSearchRoute> {
        SettingsSearchScreen()
    }
    entry<WorkerInfoRoute> {
        WorkerInfoScreen()
    }

    entry<SettingsAdvancedScreen> {
        SettingsAdvancedScreen.Content()
    }
    entry<SettingsAppearanceScreen> {
        SettingsAppearanceScreen.Content()
    }
    entry<SettingsBrowseScreen> {
        SettingsBrowseScreen.Content()
    }
    entry<SettingsDataScreen> {
        SettingsDataScreen.Content()
    }
    entry<SettingsDownloadScreen> {
        SettingsDownloadScreen.Content()
    }
    entry<SettingsLibraryScreen> {
        SettingsLibraryScreen.Content()
    }
    entry<SettingsSecurityScreen> {
        SettingsSecurityScreen.Content()
    }
    entry<SettingsTrackingScreen> {
        SettingsTrackingScreen.Content()
    }
}
