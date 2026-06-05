package yokai.presentation.settings.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import dev.icerock.moko.resources.StringResource
import dev.icerock.moko.resources.compose.pluralStringResource
import dev.icerock.moko.resources.compose.stringResource
import eu.kanade.tachiyomi.core.storage.preference.collectAsState
import eu.kanade.tachiyomi.data.database.models.Category
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.util.lang.addBetaTag
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.collections.immutable.toPersistentList
import uy.kohesive.injekt.injectLazy
import yokai.domain.category.interactor.GetCategories
import yokai.domain.download.DownloadPreferences
import yokai.domain.novel.NovelPreferences
import yokai.i18n.MR
import yokai.presentation.component.preference.Preference
import yokai.presentation.settings.ComposableSettings

object SettingsDownloadScreen : ComposableSettings() {

    private fun readResolve() = SettingsDownloadScreen

    @Composable
    override fun getTitleRes(): StringResource = MR.strings.downloads

    @Composable
    override fun getPreferences(): List<Preference> {
        val preferences: PreferencesHelper by injectLazy()
        val downloadPreferences: DownloadPreferences by injectLazy()
        val novelPreferences: NovelPreferences by injectLazy()
        val getCategories: GetCategories by injectLazy()
        val context = LocalContext.current

        val dbCategories by remember { getCategories.subscribe() }
            .collectAsState(initial = emptyList())
        val allCategories = remember(context, dbCategories) {
            listOf(Category.createDefault(context)) + dbCategories
        }
        val categoryEntries = remember(allCategories) {
            allCategories.associate { it.id.toString() to it.name.orEmpty() }.toImmutableMap()
        }

        val removeAfterReadSlots by preferences.removeAfterReadSlots().collectAsState()
        val downloadNewChapters by preferences.downloadNewChapters().collectAsState()

        return buildList<Preference> {
            add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = preferences.downloadOnlyOverWifi(),
                    title = stringResource(MR.strings.only_download_over_wifi),
                ),
            )
            add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = preferences.saveChaptersAsCBZ(),
                    title = stringResource(MR.strings.save_chapters_as_cbz),
                ),
            )
            add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = preferences.splitTallImages(),
                    title = stringResource(MR.strings.split_tall_images),
                    subtitle = stringResource(MR.strings.split_tall_images_summary),
                ),
            )
            add(
                Preference.PreferenceItem.SwitchPreference(
                    pref = downloadPreferences.downloadWithId(),
                    title = stringResource(MR.strings.download_with_id).addBetaTag(context).toString(),
                    subtitle = stringResource(MR.strings.download_with_id_details),
                ),
            )

            add(
                Preference.PreferenceGroup(
                    title = stringResource(MR.strings.remove_after_read),
                    preferenceItems = buildList<Preference.PreferenceItem<out Any>> {
                        add(
                            Preference.PreferenceItem.SwitchPreference(
                                pref = preferences.removeAfterMarkedAsRead(),
                                title = stringResource(MR.strings.remove_when_marked_as_read),
                            ),
                        )
                        add(
                            Preference.PreferenceItem.SwitchPreference(
                                pref = novelPreferences.removeAfterMarkedAsRead(),
                                title = stringResource(MR.strings.remove_novel_when_marked_as_read),
                            ),
                        )
                        add(
                            Preference.PreferenceItem.ListPreference(
                                pref = preferences.removeAfterReadSlots(),
                                title = stringResource(MR.strings.remove_after_read),
                                entries = mapOf(
                                    -1 to stringResource(MR.strings.never),
                                    0 to stringResource(MR.strings.last_read_chapter),
                                    1 to stringResource(MR.strings.second_to_last),
                                    2 to stringResource(MR.strings.third_to_last),
                                    3 to stringResource(MR.strings.fourth_to_last),
                                    4 to stringResource(MR.strings.fifth_to_last),
                                ).toImmutableMap(),
                            ),
                        )
                        if (removeAfterReadSlots != -1) {
                            add(
                                Preference.PreferenceItem.MultiSelectListPreference(
                                    pref = preferences.removeExcludeCategories(),
                                    title = stringResource(MR.strings.pref_remove_exclude_categories),
                                    entries = categoryEntries,
                                ),
                            )
                        }
                        add(
                            Preference.PreferenceItem.SwitchPreference(
                                pref = preferences.removeBookmarkedChapters(),
                                title = stringResource(MR.strings.allow_deleting_bookmarked_chapters),
                            ),
                        )
                    }.toPersistentList(),
                ),
            )

            add(
                Preference.PreferenceGroup(
                    title = stringResource(MR.strings.download_new_chapters),
                    preferenceItems = buildList<Preference.PreferenceItem<out Any>> {
                        add(
                            Preference.PreferenceItem.SwitchPreference(
                                pref = preferences.downloadNewChapters(),
                                title = stringResource(MR.strings.download_new_chapters),
                            ),
                        )
                        if (downloadNewChapters) {
                            add(
                                Preference.PreferenceItem.TriStateListPreference(
                                    includedPref = preferences.downloadNewChaptersInCategories(),
                                    excludedPref = preferences.excludeCategoriesInDownloadNew(),
                                    title = stringResource(MR.strings.categories),
                                    entries = categoryEntries,
                                ),
                            )
                        }
                        add(
                            Preference.PreferenceItem.SwitchPreference(
                                pref = novelPreferences.downloadNewChapters(),
                                title = stringResource(MR.strings.download_new_novel_chapters),
                            ),
                        )
                    }.toPersistentList(),
                ),
            )

            add(
                Preference.PreferenceGroup(
                    title = stringResource(MR.strings.download_ahead),
                    preferenceItems = persistentListOfAutoDownload(preferences),
                ),
            )

            add(
                Preference.PreferenceGroup(
                    title = stringResource(MR.strings.automatic_removal),
                    preferenceItems = buildList<Preference.PreferenceItem<out Any>> {
                        add(
                            Preference.PreferenceItem.ListPreference(
                                pref = preferences.deleteRemovedChapters(),
                                title = stringResource(MR.strings.delete_removed_chapters),
                                subtitle = stringResource(MR.strings.delete_downloaded_if_removed_online),
                                entries = mapOf(
                                    0 to stringResource(MR.strings.ask_on_chapters_page),
                                    1 to stringResource(MR.strings.always_keep),
                                    2 to stringResource(MR.strings.always_delete),
                                ).toImmutableMap(),
                            ),
                        )
                    }.toPersistentList(),
                ),
            )
        }
    }

    @Composable
    private fun persistentListOfAutoDownload(preferences: PreferencesHelper) = buildList<Preference.PreferenceItem<out Any>> {
        val entries = mapOf(
            0 to stringResource(MR.strings.never),
            2 to pluralStringResource(MR.plurals.next_unread_chapters, quantity = 2, 2),
            3 to pluralStringResource(MR.plurals.next_unread_chapters, quantity = 3, 3),
            5 to pluralStringResource(MR.plurals.next_unread_chapters, quantity = 5, 5),
            10 to pluralStringResource(MR.plurals.next_unread_chapters, quantity = 10, 10),
        ).toImmutableMap()
        add(
            Preference.PreferenceItem.ListPreference(
                pref = preferences.autoDownloadWhileReading(),
                title = stringResource(MR.strings.auto_download_while_reading),
                entries = entries,
            ),
        )
        add(
            Preference.PreferenceItem.InfoPreference(
                title = stringResource(MR.strings.download_ahead_info),
            ),
        )
    }.toPersistentList()
}
