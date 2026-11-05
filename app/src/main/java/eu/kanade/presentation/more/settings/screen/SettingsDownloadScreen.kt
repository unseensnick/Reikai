package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.more.settings.Preference
import mihon.app.di.appGraph
import reikai.domain.download.DownloadPacing
import reikai.domain.download.MAX_PARALLEL_SOURCES
import reikai.domain.library.ContentType
import reikai.domain.library.labelRes
import reikai.domain.manga.MangaPreferences
import reikai.domain.novel.NovelPreferences
import reikai.presentation.library.sortedByCategoryPref
import reikai.presentation.settings.categoryFilterPreference
import reikai.presentation.settings.download.DownloadDelaySlider
import reikai.presentation.settings.download.SourceDelaysScreen
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import tachiyomi.core.common.preference.Preference as PreferenceData

object SettingsDownloadScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_downloads

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val getCategories = remember { context.appGraph.getCategories }
        // RK --> in the category sort order, as every other category picker lists them
        val reikaiLibraryPreferences = remember { context.appGraph.reikaiLibraryPreferences }
        val allCategories by remember { getCategories.subscribe().sortedByCategoryPref(reikaiLibraryPreferences) }
            .collectAsState(initial = emptyList())
        // RK <--

        val downloadPreferences = remember { context.appGraph.downloadPreferences }
        // RK: light-novel download options
        val novelPreferences = remember { context.appGraph.novelPreferences }
        val mangaPreferences = remember { context.appGraph.mangaPreferences } // RK
        val getNovelCategories = remember { context.appGraph.getNovelCategories }
        val novelCategories by remember {
            getNovelCategories.subscribe().sortedByCategoryPref(reikaiLibraryPreferences) // RK
        }.collectAsState(initial = emptyList())
        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.downloadOnlyOverWifi,
                title = stringResource(MR.strings.connected_to_wifi),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.saveChaptersAsCBZ,
                title = stringResource(MR.strings.save_chapter_as_cbz),
                visible = showsContent(ContentType.MANGA), // RK
            ),
            // RK: the concurrency sliders moved into downloadSpeedGroup, beside the delays
            // RK --> duplicated manga/novel download options, split into content-type sub-groups so each
            // row reads clean and the two never drift (the manga/novel builders are parameter-identical).
            deleteChaptersGroup(
                contentType = ContentType.MANGA,
                removeAfterMarkedAsRead = downloadPreferences.removeAfterMarkedAsRead,
                removeAfterReadSlots = downloadPreferences.removeAfterReadSlots,
                removeBookmarkedChapters = downloadPreferences.removeBookmarkedChapters,
                excludeCategories = downloadPreferences.removeExcludeCategories,
                categories = allCategories,
            ),
            deleteChaptersGroup(
                contentType = ContentType.NOVELS,
                removeAfterMarkedAsRead = novelPreferences.removeAfterMarkedAsRead(),
                removeAfterReadSlots = novelPreferences.removeAfterReadSlots(),
                removeBookmarkedChapters = novelPreferences.removeBookmarkedChapters(),
                excludeCategories = novelPreferences.removeExcludeCategories(),
                categories = novelCategories,
            ),
            autoDownloadGroup(
                contentType = ContentType.MANGA,
                downloadNew = downloadPreferences.downloadNewChapters,
                downloadNewUnreadOnly = downloadPreferences.downloadNewUnreadChaptersOnly,
                includedCategories = downloadPreferences.downloadNewChapterCategories,
                excludedCategories = downloadPreferences.downloadNewChapterCategoriesExclude,
                categories = allCategories,
                autoDownloadWhileReading = downloadPreferences.autoDownloadWhileReading,
                // Only manga's reader needs the current and next chapter on disk before it downloads
                // ahead (ReaderViewModel.downloadNextChapters); the novel reader has no such gate.
                showDownloadAheadInfo = true,
            ),
            autoDownloadGroup(
                contentType = ContentType.NOVELS,
                downloadNew = novelPreferences.downloadNewChapters(),
                downloadNewUnreadOnly = novelPreferences.downloadNewUnreadChaptersOnly(),
                includedCategories = novelPreferences.downloadNewChapterCategories(),
                excludedCategories = novelPreferences.downloadNewChapterCategoriesExclude(),
                categories = novelCategories,
                autoDownloadWhileReading = novelPreferences.autoDownloadWhileReading(),
                showDownloadAheadInfo = false,
            ),
            downloadSpeedGroup(downloadPreferences, mangaPreferences, novelPreferences),
            // RK <--
        )
    }

    // RK --> the group builders take their preferences as parameters, so manga and novels share them
    // One builder, called once per content type, so the manga and novel delete-chapters groups can't drift.
    @Composable
    private fun deleteChaptersGroup(
        contentType: ContentType,
        removeAfterMarkedAsRead: PreferenceData<Boolean>,
        removeAfterReadSlots: PreferenceData<Int>,
        removeBookmarkedChapters: PreferenceData<Boolean>,
        excludeCategories: PreferenceData<Set<String>>,
        categories: List<Category>,
    ): Preference.PreferenceGroup {
        val slotEntries = mapOf(
            -1 to stringResource(MR.strings.disabled),
            0 to stringResource(MR.strings.last_read_chapter),
            1 to stringResource(MR.strings.second_to_last),
            2 to stringResource(MR.strings.third_to_last),
            3 to stringResource(MR.strings.fourth_to_last),
            4 to stringResource(MR.strings.fifth_to_last),
        )
        return Preference.PreferenceGroup(
            title = contentTypedCategory(MR.strings.pref_category_delete_chapters, contentType.labelRes),
            visible = showsContent(contentType),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = removeAfterMarkedAsRead,
                    title = stringResource(MR.strings.pref_remove_after_marked_as_read),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = removeAfterReadSlots,
                    entries = slotEntries,
                    title = stringResource(MR.strings.pref_remove_after_read),
                    subtitleProvider = { value, entries -> entries[value].orEmpty() },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = removeBookmarkedChapters,
                    title = stringResource(MR.strings.pref_remove_bookmarked_chapters),
                ),
                excludeCategoriesPreference(excludeCategories, categories),
            ),
        )
    }

    /** An "Excluded categories" multi-select showing the selected category names as its subtitle. */
    @Composable
    private fun excludeCategoriesPreference(
        preference: PreferenceData<Set<String>>,
        categories: List<Category>,
    ): Preference.PreferenceItem.MultiSelectListPreference<String> {
        return Preference.PreferenceItem.MultiSelectListPreference(
            preference = preference,
            entries = categories.associate { it.id.toString() to it.visualName },
            title = stringResource(MR.strings.pref_remove_exclude_categories),
            subtitleProvider = { value, entries ->
                value.mapNotNull { entries[it] }.sorted().joinToString()
            },
        )
    }

    // How fast downloads run, last since it is the most advanced: Mihon's concurrency sliders, then each
    // content type's delay between chapters and the per-source delays.
    @Composable
    private fun downloadSpeedGroup(
        downloadPreferences: DownloadPreferences,
        mangaPreferences: MangaPreferences,
        novelPreferences: NovelPreferences,
    ): Preference.PreferenceGroup {
        val navigator = LocalNavigator.currentOrThrow
        val mangaOwn by mangaPreferences.downloadSourceDelays().collectAsState()
        val novelOwn by novelPreferences.downloadSourceDelays().collectAsState()
        val showsManga = showsContent(ContentType.MANGA)
        val showsNovels = showsContent(ContentType.NOVELS)
        val setCount = (if (showsManga) DownloadPacing.parse(mangaOwn).size else 0) +
            (if (showsNovels) DownloadPacing.parse(novelOwn).size else 0)
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_download_speed),
            preferenceItems = listOfNotNull(
                Preference.PreferenceItem.SliderPreference(
                    preference = downloadPreferences.parallelSourceLimit,
                    valueRange = 1..MAX_PARALLEL_SOURCES,
                    title = stringResource(MR.strings.pref_download_concurrent_sources),
                ),
                Preference.PreferenceItem.SliderPreference(
                    preference = downloadPreferences.parallelPageLimit,
                    valueRange = 1..15,
                    title = stringResource(MR.strings.pref_download_concurrent_pages),
                    subtitle = stringResource(MR.strings.pref_download_concurrent_pages_summary),
                    visible = showsManga,
                ),
                chapterDelayItem(MR.strings.pref_manga_chapter_delay, mangaPreferences.downloadChapterDelayMs())
                    .takeIf { showsManga },
                chapterDelayItem(MR.strings.pref_novel_chapter_delay, novelPreferences.downloadChapterDelayMs())
                    .takeIf { showsNovels },
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_source_delay),
                    subtitle = if (setCount == 0) {
                        stringResource(MR.strings.pref_source_delay_none)
                    } else {
                        pluralStringResource(MR.plurals.pref_source_delay_count, count = setCount, setCount)
                    },
                    onClick = { navigator.push(SourceDelaysScreen()) },
                ),
            ),
        )
    }

    @Composable
    private fun chapterDelayItem(title: StringResource, preference: PreferenceData<Long>) =
        Preference.PreferenceItem.CustomPreference(title = stringResource(title)) {
            val delayMs by preference.collectAsState()
            DownloadDelaySlider(
                title = stringResource(title),
                selectedMs = delayMs,
                options = DownloadPacing.DELAY_OPTIONS_MS,
                defaultMs = delayMs,
                onChange = { it?.let(preference::set) },
            )
        }

    // One builder per content type; the manga and novel auto-download groups stay identical. Download-ahead
    // (preload while reading) lives here too, since it's another form of automatic downloading.
    @Composable
    private fun autoDownloadGroup(
        contentType: ContentType,
        downloadNew: PreferenceData<Boolean>,
        downloadNewUnreadOnly: PreferenceData<Boolean>,
        includedCategories: PreferenceData<Set<String>>,
        excludedCategories: PreferenceData<Set<String>>,
        categories: List<Category>,
        autoDownloadWhileReading: PreferenceData<Int>,
        showDownloadAheadInfo: Boolean,
    ): Preference.PreferenceGroup {
        val enabled by downloadNew.collectAsState()
        val aheadEntries = listOf(0, 2, 3, 5, 10)
            .associateWith {
                if (it == 0) {
                    stringResource(MR.strings.disabled)
                } else {
                    pluralStringResource(MR.plurals.next_unread_chapters, count = it, it)
                }
            }
        return Preference.PreferenceGroup(
            title = contentTypedCategory(MR.strings.pref_category_auto_download, contentType.labelRes),
            visible = showsContent(contentType),
            preferenceItems = listOfNotNull(
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadNew,
                    title = stringResource(MR.strings.pref_download_new),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadNewUnreadOnly,
                    title = stringResource(MR.strings.pref_download_new_unread_chapters_only),
                    visible = enabled,
                ),
                categoryFilterPreference(
                    categories,
                    includedCategories,
                    excludedCategories,
                    MR.strings.pref_download_new_categories_details,
                    visible = enabled,
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = autoDownloadWhileReading,
                    entries = aheadEntries,
                    title = stringResource(MR.strings.auto_download_while_reading),
                ),
                if (showDownloadAheadInfo) {
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.download_ahead_info))
                } else {
                    null
                },
            ),
        )
    }
    // RK <--
}
