package reikai.domain.category

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.novel.NovelPreferences
import reikai.domain.source.ReikaiSourcePreferences
import tachiyomi.core.common.preference.Preference
import tachiyomi.domain.download.service.DownloadPreferences
import tachiyomi.domain.library.service.LibraryPreferences

/**
 * The one list of every preference that stores a category id, split by content type. The cleanup
 * migration, both category-delete paths and backup restore all read this list, so a new category-id
 * preference is declared here once and every cleanup path picks it up for the right content type. Without
 * it the manga and novel coverage would be hand-maintained in several places and could silently drift.
 *
 * Excluded on purpose: manga's `lastUsedCategory` is a library tab index (app-state, never backed up),
 * not a category id.
 */
@Inject
@SingleIn(AppScope::class)
class CategoryIdPreferences(
    libraryPreferences: LibraryPreferences,
    downloadPreferences: DownloadPreferences,
    novelPreferences: NovelPreferences,
    reikaiLibraryPreferences: ReikaiLibraryPreferences,
    reikaiSourcePreferences: ReikaiSourcePreferences,
) {

    /** Manga default-category preference: a single id, or -1 for "prompt on favorite". */
    val mangaDefault: Preference<Int> = libraryPreferences.defaultCategory

    /** Every manga preference holding a set of category ids. */
    val mangaSets: List<Preference<Set<String>>> = listOf(
        libraryPreferences.updateCategories,
        libraryPreferences.updateCategoriesExclude,
        downloadPreferences.removeExcludeCategories,
        downloadPreferences.downloadNewChapterCategories,
        downloadPreferences.downloadNewChapterCategoriesExclude,
    )

    /**
     * Sets that may hold ids of EITHER content type: the include/exclude library filter, and one pair
     * per rendered recents surface (see `RecentsSurface`). Scrubbed against the union of valid ids,
     * and a delete of any content type scrubs them. On restore they are remapped inline on the manga
     * pass only (manga + universal names); a backup's novel ids in them are dropped, since novel
     * categories do not exist yet at preference-restore time, and a filter is cheaply re-picked.
     */
    val sharedSets: List<Preference<Set<String>>> = listOf(
        reikaiLibraryPreferences.filterCategoriesInclude,
        reikaiLibraryPreferences.filterCategoriesExclude,
        reikaiSourcePreferences.updatesFilterCategoriesInclude,
        reikaiSourcePreferences.updatesFilterCategoriesExclude,
        reikaiSourcePreferences.historyFilterCategoriesInclude,
        reikaiSourcePreferences.historyFilterCategoriesExclude,
        reikaiSourcePreferences.recentsFilterCategoriesInclude,
        reikaiSourcePreferences.recentsFilterCategoriesExclude,
    )

    /** Novel default-category preference: a single id, or -1 for "prompt on favorite". */
    val novelDefault: Preference<Int> = novelPreferences.defaultNovelCategory()

    /** Every novel preference holding a set of category ids. */
    val novelSets: List<Preference<Set<String>>> = listOf(
        novelPreferences.removeExcludeCategories(),
        novelPreferences.downloadNewChapterCategories(),
        novelPreferences.downloadNewChapterCategoriesExclude(),
        novelPreferences.novelUpdateCategories(),
        novelPreferences.novelUpdateCategoriesExclude(),
    )
}

/**
 * Orphaned Yōkai-era key (the last-viewed novel category tab index). Its accessor was removed with the
 * novel-stack retirement, so nothing reads it, but a value can still linger in the prefs XML or ride in on
 * an old backup. Cleaned up by the content-type cleanup migration and skipped on restore so it stays gone.
 */
const val DEAD_LAST_USED_NOVEL_CATEGORY_KEY = "last_used_novel_category"

/**
 * Translate a set of backup category ids to the freshly restored local ids, matched by category name.
 * A restore mints new rowids, so a stored id only survives if some restored category still carries the
 * same name; anything unmatched is dropped, except an id in [currentIds]: that names a live local
 * category the backup never knew (the pref kept its on-device value), so it is left alone. Shared by
 * the manga (inline, in PreferenceRestorer) and novel (post-restore) remap paths so the two can't
 * diverge; the manga path translates values it just wrote from the backup, so it passes no currentIds.
 */
fun translateCategoryIds(
    ids: Set<String>,
    backupIdToName: Map<String, String>,
    nameToNewId: Map<String, String>,
    currentIds: Set<String> = emptySet(),
): Set<String> = ids.mapNotNullTo(mutableSetOf()) { id ->
    backupIdToName[id]?.let(nameToNewId::get) ?: id.takeIf { it in currentIds }
}
