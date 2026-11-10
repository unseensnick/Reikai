package reikai.domain.task

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore

/** Whether the app-state banner shows a running library update or backup restore (from Komikku). */
@Inject
@SingleIn(AppScope::class)
class TaskBannerPreferences(preferenceStore: PreferenceStore) {

    val showUpdating: Preference<Boolean> = preferenceStore.getBoolean("pref_show_updating_progress_banner_key", true)

    val showRestoring: Preference<Boolean> = preferenceStore.getBoolean("pref_show_restoring_progress_banner_key", true)
}
