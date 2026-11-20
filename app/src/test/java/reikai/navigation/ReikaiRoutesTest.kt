package reikai.navigation

import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.test.core.app.ApplicationProvider
import eu.kanade.presentation.more.settings.screen.SettingsAdvancedScreen
import eu.kanade.presentation.more.settings.screen.SettingsAppearanceScreen
import eu.kanade.presentation.more.settings.screen.SettingsBrowseScreen
import eu.kanade.presentation.more.settings.screen.SettingsDataScreen
import eu.kanade.presentation.more.settings.screen.SettingsDownloadScreen
import eu.kanade.presentation.more.settings.screen.SettingsEhScreen
import eu.kanade.presentation.more.settings.screen.SettingsLibraryScreen
import eu.kanade.presentation.more.settings.screen.SettingsMainScreen
import eu.kanade.presentation.more.settings.screen.SettingsMangaDexScreen
import eu.kanade.presentation.more.settings.screen.SettingsMangaReaderScreen
import eu.kanade.presentation.more.settings.screen.SettingsNovelReaderScreen
import eu.kanade.presentation.more.settings.screen.SettingsSecurityScreen
import eu.kanade.presentation.more.settings.screen.SettingsTrackingScreen
import eu.kanade.presentation.more.settings.screen.about.AboutScreen
import eu.kanade.presentation.more.settings.screen.novel.NovelCodeSnippetsRoute
import eu.kanade.presentation.more.settings.screen.novel.NovelFontsRoute
import eu.kanade.presentation.more.settings.screen.novel.NovelRegexRulesRoute
import eu.kanade.tachiyomi.ui.home.RecentsTabRoute
import eu.kanade.tachiyomi.ui.home.StatsTabRoute
import eu.kanade.tachiyomi.ui.home.TopLevelRoute
import eu.kanade.tachiyomi.ui.reader.setting.ReaderBottomButton
import eu.kanade.tachiyomi.ui.webview.WebViewActivity
import exh.debug.SettingsDebugRoute
import exh.md.follows.MangaDexFollowsRoute
import exh.pagepreview.PagePreviewRoute
import exh.ui.batchadd.BatchAddRoute
import exh.ui.metadata.MetadataViewRoute
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json
import mihon.core.navigation.AppLanguageRoute
import mihon.core.navigation.BackupSchemaRoute
import mihon.core.navigation.CategoryRoute
import mihon.core.navigation.ClearDatabaseRoute
import mihon.core.navigation.CreateBackupRoute
import mihon.core.navigation.DebugInfoRoute
import mihon.core.navigation.DeepLinkRoute
import mihon.core.navigation.DownloadQueueRoute
import mihon.core.navigation.ExtensionDetailsRoute
import mihon.core.navigation.ExtensionFilterRoute
import mihon.core.navigation.ExtensionStoresRoute
import mihon.core.navigation.HomeRoute
import mihon.core.navigation.MangaNotesRoute
import mihon.core.navigation.MangaRoute
import mihon.core.navigation.NewUpdateRoute
import mihon.core.navigation.OnboardingRoute
import mihon.core.navigation.OpenSourceLicensesRoute
import mihon.core.navigation.RestoreBackupRoute
import mihon.core.navigation.SettingsSearchRoute
import mihon.core.navigation.SourcePreferencesRoute
import mihon.core.navigation.StatsRoute
import mihon.core.navigation.UpcomingRoute
import mihon.core.navigation.WebViewRoute
import mihon.core.navigation.WorkerInfoRoute
import mihon.core.navigation.appEntries
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters
import org.robolectric.RobolectricTestRunner
import reikai.domain.entry.EntryId
import reikai.domain.library.ContentType
import reikai.domain.source.SourceKey
import reikai.novel.content.NovelSnippetKind
import reikai.presentation.browse.catalogue.EntryCatalogueRoute
import reikai.presentation.browse.extension.details.NovelPluginDetailsRoute
import reikai.presentation.browse.globalsearch.EntryGlobalSearchRoute
import reikai.presentation.browse.globalsearch.SearchSourceFilter
import reikai.presentation.browse.source.EntrySourcesFilterRoute
import reikai.presentation.browse.source.NovelSourcePreferencesRoute
import reikai.presentation.library.hidden.HiddenEntriesRoute
import reikai.presentation.library.preferredsources.PreferredSourcesRoute
import reikai.presentation.library.updateerror.UpdateErrorsRoute
import reikai.presentation.local.LocalImportRoute
import reikai.presentation.migrate.flow.EntryMigrationConfigRoute
import reikai.presentation.migrate.flow.EntryMigrationFavoritesRoute
import reikai.presentation.migrate.flow.EntryMigrationListRoute
import reikai.presentation.migrate.flow.EntryMigrationSearchRoute
import reikai.presentation.migrate.flow.EntryMigrationSourcePickRoute
import reikai.presentation.migrate.flow.MigrationPickFor
import reikai.presentation.notes.VolumeNotesRoute
import reikai.presentation.novel.details.NovelRoute
import reikai.presentation.novel.notes.NovelNotesRoute
import reikai.presentation.novel.search.NovelChapterSearchRoute
import reikai.presentation.reader.ReaderBottomButtonsRoute
import reikai.presentation.recommendation.SettingsRecommendationsScreen
import reikai.presentation.recommendation.browse.RelatedMangasBrowseRoute
import reikai.presentation.roundTrip
import reikai.presentation.settings.appearance.CustomThemeColorRoute
import reikai.presentation.settings.appearance.LauncherIconRoute
import reikai.presentation.settings.download.SourceDelaysRoute
import reikai.presentation.stats.StatsDimension
import reikai.presentation.stats.StatsGroupKey
import reikai.presentation.stats.details.StatsGroupRoute
import reikai.presentation.stats.details.StatsMostReadRoute
import reikai.presentation.stats.details.StatsReadingRoute
import reikai.presentation.stats.details.StatsSeriesRoute
import reikai.presentation.track.EntryTrackChapterSelectorRoute
import reikai.presentation.track.EntryTrackDateRemoverRoute
import reikai.presentation.track.EntryTrackDateSelectorRoute
import reikai.presentation.track.EntryTrackInfoDialogHomeRoute
import reikai.presentation.track.EntryTrackScoreSelectorRoute
import reikai.presentation.track.EntryTrackSheetRoute
import reikai.presentation.track.EntryTrackStatusSelectorRoute
import reikai.presentation.track.EntryTrackerRemoveRoute
import reikai.presentation.track.EntryTrackerSearchRoute
import java.io.File

/**
 * Every route the app module declares survives a save and restore, and every one the main display draws is
 * registered there. A route only another navigator draws (a tab, a track sheet page) is listed as such, and
 * [RouteListGuardTest] fails on a declared route this list does not name, so none is left untested.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class ReikaiRoutesTest(private val name: String, private val route: NavKey, private val drawnBy: DrawnBy) {

    enum class DrawnBy { MAIN, TABS, TRACK_SHEET }

    @Test
    fun `the route survives a save and restore`() {
        roundTrip(route) shouldBe route
    }

    @Test
    fun `a route the main display draws is registered there`() {
        val registered = runCatching { mainEntries(route) }.isSuccess

        registered shouldBe (drawnBy == DrawnBy.MAIN)
    }

    companion object {
        private val mainEntries = entryProvider<NavKey> {
            appEntries()
            reikaiEntries()
        }

        val routes: List<Pair<NavKey, DrawnBy>> = listOf(
            NovelRoute(1L, fromSource = true) to DrawnBy.MAIN,
            EntryCatalogueRoute(
                SourceKey.Novel("plugin"),
                initialQuery = "q",
                startLatest = true,
                migrateFor = MigrationPickFor(EntryId.Novel(2L), flowId = 3L),
                savedSearchId = 4L,
            ) to DrawnBy.MAIN,
            EntryTrackSheetRoute(EntryId.Manga(1L)) to DrawnBy.MAIN,
            NovelSourcePreferencesRoute("plugin") to DrawnBy.MAIN,
            AboutScreen to DrawnBy.MAIN,
            SettingsEhScreen to DrawnBy.MAIN,
            SettingsMangaDexScreen to DrawnBy.MAIN,
            SettingsMangaReaderScreen to DrawnBy.MAIN,
            SettingsNovelReaderScreen to DrawnBy.MAIN,
            SettingsRecommendationsScreen to DrawnBy.MAIN,
            SettingsAdvancedScreen to DrawnBy.MAIN,
            SettingsAppearanceScreen to DrawnBy.MAIN,
            SettingsBrowseScreen to DrawnBy.MAIN,
            SettingsDataScreen to DrawnBy.MAIN,
            SettingsDownloadScreen to DrawnBy.MAIN,
            SettingsLibraryScreen to DrawnBy.MAIN,
            SettingsMainScreen to DrawnBy.MAIN,
            SettingsSecurityScreen to DrawnBy.MAIN,
            SettingsTrackingScreen to DrawnBy.MAIN,
            BatchAddRoute to DrawnBy.MAIN,
            CustomThemeColorRoute to DrawnBy.MAIN,
            EntryGlobalSearchRoute("q", "ext", ContentType.NOVELS, SearchSourceFilter.PinnedOnly) to DrawnBy.MAIN,
            EntryMigrationConfigRoute(ContentType.MANGA, listOf(1L, 2L)) to DrawnBy.MAIN,
            EntryMigrationFavoritesRoute(ContentType.NOVELS, "plugin") to DrawnBy.MAIN,
            EntryMigrationListRoute(ContentType.MANGA, listOf(1L), extraQuery = "x") to DrawnBy.MAIN,
            EntryMigrationSearchRoute(ContentType.NOVELS, 1L, extraQuery = null) to DrawnBy.MAIN,
            EntryMigrationSourcePickRoute(ContentType.MANGA, listOf(1L)) to DrawnBy.MAIN,
            EntrySourcesFilterRoute(ContentType.NOVELS) to DrawnBy.MAIN,
            HiddenEntriesRoute(ContentType.MANGA) to DrawnBy.MAIN,
            LauncherIconRoute to DrawnBy.MAIN,
            LocalImportRoute(listOf("content://files/a.cbz")) to DrawnBy.MAIN,
            MangaDexFollowsRoute(1L) to DrawnBy.MAIN,
            MetadataViewRoute(1L, 2L) to DrawnBy.MAIN,
            NovelChapterSearchRoute(1L, sourceScoped = true) to DrawnBy.MAIN,
            NovelCodeSnippetsRoute(NovelSnippetKind.JS) to DrawnBy.MAIN,
            NovelFontsRoute to DrawnBy.MAIN,
            NovelNotesRoute(1L) to DrawnBy.MAIN,
            NovelPluginDetailsRoute("plugin") to DrawnBy.MAIN,
            NovelRegexRulesRoute to DrawnBy.MAIN,
            PagePreviewRoute(1L) to DrawnBy.MAIN,
            PreferredSourcesRoute to DrawnBy.MAIN,
            ReaderBottomButtonsRoute(ReaderBottomButton.Scope.Novel) to DrawnBy.MAIN,
            RelatedMangasBrowseRoute(1L) to DrawnBy.MAIN,
            SettingsDebugRoute to DrawnBy.MAIN,
            SourceDelaysRoute to DrawnBy.MAIN,
            StatsGroupRoute(StatsDimension.SCORE) to DrawnBy.MAIN,
            StatsMostReadRoute to DrawnBy.MAIN,
            StatsReadingRoute to DrawnBy.MAIN,
            StatsSeriesRoute(StatsGroupKey.Score(null), "None", ContentType.ALL) to DrawnBy.MAIN,
            UpdateErrorsRoute(ContentType.NOVELS) to DrawnBy.MAIN,
            VolumeNotesRoute(ContentType.MANGA, "Series", "Vol. 1") to DrawnBy.MAIN,
            HomeRoute to DrawnBy.MAIN,
            DeepLinkRoute("q") to DrawnBy.MAIN,
            WebViewRoute("https://example.org", "T", 1L) to DrawnBy.MAIN,
            MangaNotesRoute(1L) to DrawnBy.MAIN,
            MangaRoute(1L, fromSource = true) to DrawnBy.MAIN,
            UpcomingRoute to DrawnBy.MAIN,
            ExtensionDetailsRoute("pkg") to DrawnBy.MAIN,
            ExtensionFilterRoute to DrawnBy.MAIN,
            SourcePreferencesRoute(1L) to DrawnBy.MAIN,
            CategoryRoute to DrawnBy.MAIN,
            DownloadQueueRoute to DrawnBy.MAIN,
            NewUpdateRoute("1.0", "notes", "https://example.org/r", "https://example.org/d") to DrawnBy.MAIN,
            OnboardingRoute to DrawnBy.MAIN,
            StatsRoute to DrawnBy.MAIN,
            AppLanguageRoute to DrawnBy.MAIN,
            BackupSchemaRoute to DrawnBy.MAIN,
            ClearDatabaseRoute to DrawnBy.MAIN,
            CreateBackupRoute to DrawnBy.MAIN,
            DebugInfoRoute to DrawnBy.MAIN,
            ExtensionStoresRoute to DrawnBy.MAIN,
            OpenSourceLicensesRoute to DrawnBy.MAIN,
            RestoreBackupRoute("content://backup") to DrawnBy.MAIN,
            SettingsSearchRoute to DrawnBy.MAIN,
            WorkerInfoRoute to DrawnBy.MAIN,
            TopLevelRoute.Library to DrawnBy.TABS,
            TopLevelRoute.Updates to DrawnBy.TABS,
            TopLevelRoute.History to DrawnBy.TABS,
            TopLevelRoute.Browse to DrawnBy.TABS,
            TopLevelRoute.More to DrawnBy.TABS,
            RecentsTabRoute to DrawnBy.TABS,
            StatsTabRoute to DrawnBy.TABS,
            EntryTrackInfoDialogHomeRoute(EntryId.Novel(1L)) to DrawnBy.TRACK_SHEET,
            EntryTrackStatusSelectorRoute(3L, EntryId.Manga(2L)) to DrawnBy.TRACK_SHEET,
            EntryTrackChapterSelectorRoute(3L, EntryId.Manga(2L)) to DrawnBy.TRACK_SHEET,
            EntryTrackScoreSelectorRoute(3L, EntryId.Manga(2L)) to DrawnBy.TRACK_SHEET,
            EntryTrackDateSelectorRoute(3L, start = true, EntryId.Manga(2L)) to DrawnBy.TRACK_SHEET,
            EntryTrackDateRemoverRoute(3L, start = false, EntryId.Manga(2L)) to DrawnBy.TRACK_SHEET,
            EntryTrackerSearchRoute(EntryId.Novel(2L), 3L) to DrawnBy.TRACK_SHEET,
            EntryTrackerRemoveRoute(EntryId.Novel(2L), 3L) to DrawnBy.TRACK_SHEET,
        )

        @JvmStatic
        @Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = routes.map { (route, drawnBy) ->
            arrayOf(route::class.simpleName!!, route, drawnBy)
        }
    }
}

/**
 * Every serializable route the app module and core/navigation declare is in [ReikaiRoutesTest.routes], so a
 * route upstream declares and Reikai draws nowhere fails here rather than crashing the push.
 */
class RouteListGuardTest {

    @Test
    fun `every declared route is listed`() {
        val roots = listOf(File("src/main/java"), File("../core/navigation/src/main/kotlin"))
        roots.all { it.isDirectory } shouldBe true
        val declared = roots.asSequence().flatMap { it.walkTopDown() }
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file -> routesIn(file.readLines()) }
            .toSortedSet()
        val listed = ReikaiRoutesTest.routes.map { it.first::class.simpleName!! }.toSortedSet()

        declared shouldBe listed
    }

    /** The @Serializable classes and objects in [lines] whose supertypes name a route kind. */
    private fun routesIn(lines: List<String>): List<String> = lines.indices
        .filter { lines[it].trim() == "@Serializable" }
        .mapNotNull { annotation ->
            val start = (annotation + 1 until lines.size).firstOrNull { !lines[it].trim().startsWith("@") }
                ?: return@mapNotNull null
            // The header up to its body or its end, with every argument list taken out.
            var header = headerFrom(lines, start)
            while (true) header = PARENS.replace(header, "").takeIf { it != header } ?: break
            HEADER.find(header)?.groupValues?.get(1)
        }

    private fun headerFrom(lines: List<String>, start: Int): String {
        val header = StringBuilder()
        var depth = 0
        for (line in lines.drop(start).take(40)) {
            header.append(line.substringBefore("//")).append(' ')
            depth += line.count { it == '(' } - line.count { it == ')' }
            val open = line.trimEnd().endsWith("(") || line.trimEnd().endsWith(",")
            if (depth <= 0 && ('{' in line || !open)) break
        }
        return header.toString()
    }

    private companion object {
        val PARENS = Regex("""\([^()]*\)""")
        val HEADER = Regex("""(?:class|object) (\w+)\s*:\s*[\w., ]*\b(?:NavKey|SearchableSettings|TopLevelRoute)\b""")
    }
}

/** A manga page's route encodes as upstream's does, the novel slot left out at its default. */
@RunWith(RobolectricTestRunner::class)
class WebViewRouteEncodingTest {

    @Test
    fun `a manga page encodes without the novel slot`() {
        Json.encodeToString(WebViewRoute.serializer(), WebViewRoute("https://example.org", "T", 1L)) shouldBe
            """{"url":"https://example.org","initialTitle":"T","sourceId":1}"""
    }

    // The intent carries the route as JSON, which WebViewActivity decodes; chapterId drives the page actions.
    @Test
    fun `a novel chapter's intent opens that chapter's page`() {
        val intent = WebViewActivity.newNovelChapterIntent(
            ApplicationProvider.getApplicationContext(),
            "https://example.org/c/3",
            "Chapter 3",
            novelId = 2L,
            chapterId = 3L,
            novelSourceId = "plugin",
        )

        Json.decodeFromString<WebViewRoute>(intent.getStringExtra("route_key")!!).novel shouldBe
            NovelWebTarget.Page("plugin", novelId = 2L, chapterId = 3L)
    }

    @Test
    fun `a novel page survives a save and restore`() {
        val route =
            WebViewRoute(
                "https://example.org",
                "T",
                novel = NovelWebTarget.Page("plugin", novelId = 2L, chapterId = 3L),
            )

        roundTrip(route) shouldBe route
    }
}
