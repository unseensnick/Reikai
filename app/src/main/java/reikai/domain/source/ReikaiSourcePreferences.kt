package reikai.domain.source

import reikai.domain.library.ContentType
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.core.common.preference.getEnum
import tachiyomi.domain.library.model.LibraryDisplayMode

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

    /** Sticky content-type filter on the Updates tab (manga + novels), its own key. */
    val updatesContentType: Preference<ContentType> =
        preferenceStore.getEnum("updates_content_type", ContentType.ALL)

    /**
     * Display mode (comfortable / compact / list) for the per-source novel browse grid. Its own key,
     * separate from Mihon's manga catalogue mode (`pref_display_mode_catalogue`), so the two surfaces
     * remember independently. Stored via the [LibraryDisplayMode] serializer, mirroring the manga side.
     */
    val novelBrowseDisplayMode: Preference<LibraryDisplayMode> = preferenceStore.getObjectFromString(
        "reikai_novel_browse_display_mode",
        LibraryDisplayMode.default,
        LibraryDisplayMode.Serializer::serialize,
        LibraryDisplayMode.Serializer::deserialize,
    )
}
