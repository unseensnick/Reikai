package reikai.domain.manga

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Net-new manga preferences with no Mihon home, the manga twin of [reikai.domain.novel.NovelPreferences].
 * Currently only the hidden-chapters set; grows as further manga/novel parity prefs land.
 */
@Inject
@SingleIn(AppScope::class)
class MangaPreferences(
    private val preferenceStore: PreferenceStore,
) {

    /**
     * Chapters the user manually hid on the details screen. Each entry is the restore-stable key
     * `"<source>|<chapterUrl>"` (no local manga id, so it survives a backup restore where manga are
     * re-inserted with new ids). Backed up automatically as a normal pref.
     */
    fun hiddenChapters() = preferenceStore.getStringSet("manga_hidden_chapters", emptySet())
}
