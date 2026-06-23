package eu.kanade.presentation.more.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.util.fastMap
import eu.kanade.presentation.category.visualName
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.widget.TriStateListDialog
import reikai.domain.novel.NovelPreferences
import reikai.domain.novel.interactor.GetNovelCategories
import reikai.domain.novel.model.toCategory
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.category.model.Category
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.pluralStringResource
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

object SettingsDownloadScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.pref_category_downloads

    @Composable
    override fun getPreferences(): List<Preference> {
        val getCategories = remember { Injekt.get<GetCategories>() }
        val allCategories by getCategories.subscribe().collectAsState(initial = emptyList())

        val downloadPreferences = remember { Injekt.get<DownloadPreferences>() }
        val parallelSourceLimit by downloadPreferences.parallelSourceLimit.collectAsState()
        val parallelPageLimit by downloadPreferences.parallelPageLimit.collectAsState()
        // RK: light-novel download options (P5 S5)
        val novelPreferences = remember { Injekt.get<NovelPreferences>() }
        val getNovelCategories = remember { Injekt.get<GetNovelCategories>() }
        val novelCategories by getNovelCategories.subscribe()
            .collectAsState(initial = emptyList())
        return listOf(
            Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.downloadOnlyOverWifi,
                title = stringResource(MR.strings.connected_to_wifi),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.saveChaptersAsCBZ,
                title = stringResource(MR.strings.save_chapter_as_cbz),
            ),
            Preference.PreferenceItem.SwitchPreference(
                preference = downloadPreferences.splitTallImages,
                title = stringResource(MR.strings.split_tall_images),
                subtitle = stringResource(MR.strings.split_tall_images_summary),
            ),
            Preference.PreferenceItem.SliderPreference(
                value = parallelSourceLimit,
                valueRange = 1..10,
                title = stringResource(MR.strings.pref_download_concurrent_sources),
                onValueChanged = { downloadPreferences.parallelSourceLimit.set(it) },
            ),
            Preference.PreferenceItem.SliderPreference(
                value = parallelPageLimit,
                valueRange = 1..15,
                title = stringResource(MR.strings.pref_download_concurrent_pages),
                subtitle = stringResource(MR.strings.pref_download_concurrent_pages_summary),
                onValueChanged = { downloadPreferences.parallelPageLimit.set(it) },
            ),
            getDeleteChaptersGroup(
                downloadPreferences = downloadPreferences,
                novelPreferences = novelPreferences, // RK: novel delete-on-read sits in this group too
                categories = allCategories,
                novelCategories = novelCategories.map { it.toCategory() }, // RK: novel exclude-categories
            ),
            getAutoDownloadGroup(
                downloadPreferences = downloadPreferences,
                novelPreferences = novelPreferences, // RK: novel auto-download sits in this group too
                allCategories = allCategories,
                novelCategories = novelCategories.map { it.toCategory() }, // RK
            ),
            getDownloadAheadGroup(
                downloadPreferences = downloadPreferences,
                novelPreferences = novelPreferences, // RK: novel download-ahead twin
            ),
        )
    }

    @Composable
    private fun getDeleteChaptersGroup(
        downloadPreferences: DownloadPreferences,
        novelPreferences: NovelPreferences,
        categories: List<Category>,
        novelCategories: List<Category>,
    ): Preference.PreferenceGroup {
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_delete_chapters),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadPreferences.removeAfterMarkedAsRead,
                    title = stringResource(MR.strings.pref_remove_after_marked_as_read),
                    subtitle = stringResource(MR.strings.content_type_manga),
                ),
                Preference.PreferenceItem.ListPreference(
                    preference = downloadPreferences.removeAfterReadSlots,
                    entries = mapOf(
                        -1 to stringResource(MR.strings.disabled),
                        0 to stringResource(MR.strings.last_read_chapter),
                        1 to stringResource(MR.strings.second_to_last),
                        2 to stringResource(MR.strings.third_to_last),
                        3 to stringResource(MR.strings.fourth_to_last),
                        4 to stringResource(MR.strings.fifth_to_last),
                    ),
                    title = stringResource(MR.strings.pref_remove_after_read),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadPreferences.removeBookmarkedChapters,
                    title = stringResource(MR.strings.pref_remove_bookmarked_chapters),
                ),
                getExcludedCategoriesPreference(
                    downloadPreferences = downloadPreferences,
                    categories = { categories },
                ),
                // RK --> light-novel delete-on-read twins (separate downloader + preferences). The
                // novel reader has one delete trigger, so removeAfterReadSlots subsumes the legacy
                // removeAfterMarkedAsRead boolean (kept only as a fallback when the slots list is off).
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.removeAfterReadSlots(),
                    entries = mapOf(
                        -1 to stringResource(MR.strings.disabled),
                        0 to stringResource(MR.strings.last_read_chapter),
                        1 to stringResource(MR.strings.second_to_last),
                        2 to stringResource(MR.strings.third_to_last),
                        3 to stringResource(MR.strings.fourth_to_last),
                        4 to stringResource(MR.strings.fifth_to_last),
                    ),
                    title = stringResource(MR.strings.pref_remove_after_read),
                    // Keep the selected-value subtitle, prefixed with the content type to tell it from manga.
                    subtitleProvider = { value, entries ->
                        "${stringResource(MR.strings.content_type_novels)}: ${entries[value]}"
                    },
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.removeBookmarkedChapters(),
                    title = stringResource(MR.strings.pref_remove_bookmarked_chapters),
                    subtitle = stringResource(MR.strings.content_type_novels),
                ),
                Preference.PreferenceItem.MultiSelectListPreference(
                    preference = novelPreferences.removeExcludeCategories(),
                    entries = novelCategories.associate { it.id.toString() to it.visualName },
                    title = stringResource(MR.strings.pref_remove_exclude_categories),
                    subtitleProvider = { value, entries ->
                        val selected = value.mapNotNull { entries[it] }.sorted().joinToString()
                        val prefix = stringResource(MR.strings.content_type_novels)
                        if (selected.isEmpty()) prefix else "$prefix: $selected"
                    },
                ),
                // RK <--
            ),
        )
    }

    @Composable
    private fun getExcludedCategoriesPreference(
        downloadPreferences: DownloadPreferences,
        categories: () -> List<Category>,
    ): Preference.PreferenceItem.MultiSelectListPreference {
        return Preference.PreferenceItem.MultiSelectListPreference(
            preference = downloadPreferences.removeExcludeCategories,
            entries = categories()
                .associate { it.id.toString() to it.visualName },
            title = stringResource(MR.strings.pref_remove_exclude_categories),
        )
    }

    @Composable
    private fun getAutoDownloadGroup(
        downloadPreferences: DownloadPreferences,
        novelPreferences: NovelPreferences,
        allCategories: List<Category>,
        novelCategories: List<Category>, // RK: novel auto-download category filter
    ): Preference.PreferenceGroup {
        val downloadNewChaptersPref = downloadPreferences.downloadNewChapters
        val downloadNewUnreadChaptersOnlyPref = downloadPreferences.downloadNewUnreadChaptersOnly
        val downloadNewChapterCategoriesPref = downloadPreferences.downloadNewChapterCategories
        val downloadNewChapterCategoriesExcludePref = downloadPreferences.downloadNewChapterCategoriesExclude

        val downloadNewChapters by downloadNewChaptersPref.collectAsState()

        val included by downloadNewChapterCategoriesPref.collectAsState()
        val excluded by downloadNewChapterCategoriesExcludePref.collectAsState()
        var showDialog by rememberSaveable { mutableStateOf(false) }
        if (showDialog) {
            TriStateListDialog(
                title = stringResource(MR.strings.categories),
                message = stringResource(MR.strings.pref_download_new_categories_details),
                items = allCategories,
                initialChecked = included.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
                initialInversed = excluded.mapNotNull { id -> allCategories.find { it.id.toString() == id } },
                itemLabel = { it.visualName },
                onDismissRequest = { showDialog = false },
                onValueChanged = { newIncluded, newExcluded ->
                    downloadNewChapterCategoriesPref.set(newIncluded.fastMap { it.id.toString() }.toSet())
                    downloadNewChapterCategoriesExcludePref.set(newExcluded.fastMap { it.id.toString() }.toSet())
                    showDialog = false
                },
            )
        }

        // RK --> light-novel auto-download (own toggle + skip-duplicate + category filter)
        val novelDownloadNewPref = novelPreferences.downloadNewChapters()
        val novelDownloadNew by novelDownloadNewPref.collectAsState()
        val novelIncludedPref = novelPreferences.downloadNewChapterCategories()
        val novelExcludedPref = novelPreferences.downloadNewChapterCategoriesExclude()
        val novelIncluded by novelIncludedPref.collectAsState()
        val novelExcluded by novelExcludedPref.collectAsState()
        var showNovelDialog by rememberSaveable { mutableStateOf(false) }
        if (showNovelDialog) {
            TriStateListDialog(
                title = stringResource(MR.strings.categories),
                message = stringResource(MR.strings.pref_download_new_categories_details),
                items = novelCategories,
                initialChecked = novelIncluded.mapNotNull { id -> novelCategories.find { it.id.toString() == id } },
                initialInversed = novelExcluded.mapNotNull { id -> novelCategories.find { it.id.toString() == id } },
                itemLabel = { it.visualName },
                onDismissRequest = { showNovelDialog = false },
                onValueChanged = { newIncluded, newExcluded ->
                    novelIncludedPref.set(newIncluded.fastMap { it.id.toString() }.toSet())
                    novelExcludedPref.set(newExcluded.fastMap { it.id.toString() }.toSet())
                    showNovelDialog = false
                },
            )
        }
        // RK <--

        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.pref_category_auto_download),
            preferenceItems = listOf(
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadNewChaptersPref,
                    title = stringResource(MR.strings.pref_download_new),
                    subtitle = stringResource(MR.strings.content_type_manga),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadNewUnreadChaptersOnlyPref,
                    title = stringResource(MR.strings.pref_download_new_unread_chapters_only),
                    enabled = downloadNewChapters,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.categories),
                    subtitle = getCategoriesLabel(
                        allCategories = allCategories,
                        included = included,
                        excluded = excluded,
                    ),
                    enabled = downloadNewChapters,
                    onClick = { showDialog = true },
                ),
                // RK --> the light-novel twin of the block above (separate downloader + preferences)
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelDownloadNewPref,
                    title = stringResource(MR.strings.pref_download_new),
                    subtitle = stringResource(MR.strings.content_type_novels),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = novelPreferences.downloadNewUnreadChaptersOnly(),
                    title = stringResource(MR.strings.pref_download_new_unread_chapters_only),
                    enabled = novelDownloadNew,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.categories),
                    subtitle = getCategoriesLabel(
                        allCategories = novelCategories,
                        included = novelIncluded,
                        excluded = novelExcluded,
                    ),
                    enabled = novelDownloadNew,
                    onClick = { showNovelDialog = true },
                ),
                // RK <--
            ),
        )
    }

    @Composable
    private fun getDownloadAheadGroup(
        downloadPreferences: DownloadPreferences,
        novelPreferences: NovelPreferences,
    ): Preference.PreferenceGroup {
        val entries = listOf(0, 2, 3, 5, 10)
            .associateWith {
                if (it == 0) {
                    stringResource(MR.strings.disabled)
                } else {
                    pluralStringResource(MR.plurals.next_unread_chapters, count = it, it)
                }
            }
        return Preference.PreferenceGroup(
            title = stringResource(MR.strings.download_ahead),
            preferenceItems = listOf(
                Preference.PreferenceItem.ListPreference(
                    preference = downloadPreferences.autoDownloadWhileReading,
                    entries = entries,
                    title = stringResource(MR.strings.auto_download_while_reading),
                ),
                // RK: the light-novel twin (separate downloader + preference)
                Preference.PreferenceItem.ListPreference(
                    preference = novelPreferences.autoDownloadWhileReading(),
                    entries = entries,
                    title = stringResource(MR.strings.auto_download_while_reading),
                    subtitleProvider = { value, entriesMap ->
                        "${stringResource(MR.strings.content_type_novels)}: ${entriesMap[value]}"
                    },
                ),
                Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.download_ahead_info)),
            ),
        )
    }
}
