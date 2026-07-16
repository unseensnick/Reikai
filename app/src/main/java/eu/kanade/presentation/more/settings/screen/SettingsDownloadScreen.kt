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
import dev.icerock.moko.resources.StringResource
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
import tachiyomi.core.common.preference.Preference as PreferenceData

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
            // RK --> duplicated manga/novel download options, split into content-type sub-groups so each
            // row reads clean and the two never drift (the manga/novel builders are parameter-identical).
            deleteChaptersGroup(
                contentType = MR.strings.content_type_manga,
                removeAfterMarkedAsRead = downloadPreferences.removeAfterMarkedAsRead,
                removeAfterReadSlots = downloadPreferences.removeAfterReadSlots,
                removeBookmarkedChapters = downloadPreferences.removeBookmarkedChapters,
                excludeCategories = downloadPreferences.removeExcludeCategories,
                categories = allCategories,
            ),
            deleteChaptersGroup(
                contentType = MR.strings.content_type_novels,
                removeAfterMarkedAsRead = novelPreferences.removeAfterMarkedAsRead(),
                removeAfterReadSlots = novelPreferences.removeAfterReadSlots(),
                removeBookmarkedChapters = novelPreferences.removeBookmarkedChapters(),
                excludeCategories = novelPreferences.removeExcludeCategories(),
                categories = novelCategories.map { it.toCategory() },
            ),
            autoDownloadGroup(
                contentType = MR.strings.content_type_manga,
                downloadNew = downloadPreferences.downloadNewChapters,
                downloadNewUnreadOnly = downloadPreferences.downloadNewUnreadChaptersOnly,
                includedCategories = downloadPreferences.downloadNewChapterCategories,
                excludedCategories = downloadPreferences.downloadNewChapterCategoriesExclude,
                categories = allCategories,
                autoDownloadWhileReading = downloadPreferences.autoDownloadWhileReading,
                showDownloadAheadInfo = false,
            ),
            autoDownloadGroup(
                contentType = MR.strings.content_type_novels,
                downloadNew = novelPreferences.downloadNewChapters(),
                downloadNewUnreadOnly = novelPreferences.downloadNewUnreadChaptersOnly(),
                includedCategories = novelPreferences.downloadNewChapterCategories(),
                excludedCategories = novelPreferences.downloadNewChapterCategoriesExclude(),
                categories = novelCategories.map { it.toCategory() },
                autoDownloadWhileReading = novelPreferences.autoDownloadWhileReading(),
                showDownloadAheadInfo = true,
            ),
            // RK <--
        )
    }

    // One builder, called once per content type, so the manga and novel delete-chapters groups can't drift.
    @Composable
    private fun deleteChaptersGroup(
        contentType: StringResource,
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
            title = contentTypedCategory(MR.strings.pref_category_delete_chapters, contentType),
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

    // One builder per content type; the manga and novel auto-download groups stay identical. Download-ahead
    // (preload while reading) lives here too, since it's another form of automatic downloading.
    @Composable
    private fun autoDownloadGroup(
        contentType: StringResource,
        downloadNew: PreferenceData<Boolean>,
        downloadNewUnreadOnly: PreferenceData<Boolean>,
        includedCategories: PreferenceData<Set<String>>,
        excludedCategories: PreferenceData<Set<String>>,
        categories: List<Category>,
        autoDownloadWhileReading: PreferenceData<Int>,
        showDownloadAheadInfo: Boolean,
    ): Preference.PreferenceGroup {
        val enabled by downloadNew.collectAsState()
        val included by includedCategories.collectAsState()
        val excluded by excludedCategories.collectAsState()
        var showDialog by rememberSaveable { mutableStateOf(false) }
        if (showDialog) {
            TriStateListDialog(
                title = stringResource(MR.strings.categories),
                message = stringResource(MR.strings.pref_download_new_categories_details),
                items = categories,
                initialChecked = included.mapNotNull { id -> categories.find { it.id.toString() == id } },
                initialInversed = excluded.mapNotNull { id -> categories.find { it.id.toString() == id } },
                itemLabel = { it.visualName },
                onDismissRequest = { showDialog = false },
                onValueChanged = { newIncluded, newExcluded ->
                    includedCategories.set(newIncluded.fastMap { it.id.toString() }.toSet())
                    excludedCategories.set(newExcluded.fastMap { it.id.toString() }.toSet())
                    showDialog = false
                },
            )
        }
        val aheadEntries = listOf(0, 2, 3, 5, 10)
            .associateWith {
                if (it == 0) {
                    stringResource(MR.strings.disabled)
                } else {
                    pluralStringResource(MR.plurals.next_unread_chapters, count = it, it)
                }
            }
        return Preference.PreferenceGroup(
            title = contentTypedCategory(MR.strings.pref_category_auto_download, contentType),
            preferenceItems = listOfNotNull(
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadNew,
                    title = stringResource(MR.strings.pref_download_new),
                ),
                Preference.PreferenceItem.SwitchPreference(
                    preference = downloadNewUnreadOnly,
                    title = stringResource(MR.strings.pref_download_new_unread_chapters_only),
                    enabled = enabled,
                ),
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.categories),
                    subtitle = getCategoriesLabel(
                        allCategories = categories,
                        included = included,
                        excluded = excluded,
                    ),
                    enabled = enabled,
                    onClick = { showDialog = true },
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
}
