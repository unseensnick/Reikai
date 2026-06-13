package reikai.domain.source

import reikai.domain.library.ContentType
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum

/**
 * Reikai's net-new Browse-scoped preferences. Kept separate from Mihon's
 * [eu.kanade.domain.source.service.SourcePreferences] so Mihon's class stays untouched and
 * upstream-mergeable.
 */
class ReikaiSourcePreferences(
    private val preferenceStore: PreferenceStore,
) {

    /**
     * Sticky content-type filter on the Browse tabs (Sources + Extensions). Its own key, distinct
     * from the Library filter (S6), so each surface remembers its last type independently.
     */
    val browseContentType: Preference<ContentType> =
        preferenceStore.getEnum("browse_content_type", ContentType.ALL)

    /** Sticky content-type filter on the unified download queue (manga + novels), its own key. */
    val downloadContentType: Preference<ContentType> =
        preferenceStore.getEnum("download_content_type", ContentType.ALL)
}
