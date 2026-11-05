package reikai.domain.manga

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import tachiyomi.core.common.preference.PreferenceStore

/**
 * Net-new manga preferences with no Mihon home, the manga twin of [reikai.domain.novel.NovelPreferences],
 * type only: a holder, so each key's rule is pinned where it is read.
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

    /** The shortest wait between two downloaded chapters from one source; see `DownloadPacing`. Off by
     *  default, which leaves Mihon's overlapping chapters untouched. */
    fun downloadChapterDelayMs() = preferenceStore.getLong("manga_download_chapter_delay_ms", 0L)

    /** A source's own delay, overriding [downloadChapterDelayMs], as `sourceId=ms` entries. */
    fun downloadSourceDelays() = preferenceStore.getStringSet("manga_download_source_delays", emptySet())
}
