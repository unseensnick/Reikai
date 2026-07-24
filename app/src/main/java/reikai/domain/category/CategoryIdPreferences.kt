package reikai.domain.category

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
        reikaiLibraryPreferences.filterCategoriesInclude,
        reikaiLibraryPreferences.filterCategoriesExclude,
        reikaiSourcePreferences.updatesFilterMangaCategoriesInclude,
        reikaiSourcePreferences.updatesFilterMangaCategoriesExclude,
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
        reikaiLibraryPreferences.novelLibraryFilterCategoriesInclude,
        reikaiLibraryPreferences.novelLibraryFilterCategoriesExclude,
        reikaiSourcePreferences.updatesFilterNovelCategoriesInclude,
        reikaiSourcePreferences.updatesFilterNovelCategoriesExclude,
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
 * same name; anything unmatched is dropped. Shared by the manga (inline, in PreferenceRestorer) and novel
 * (post-restore) remap paths so the two can't diverge.
 */
fun translateCategoryIds(
    ids: Set<String>,
    backupIdToName: Map<String, String>,
    nameToNewId: Map<String, String>,
): Set<String> = ids.mapNotNullTo(mutableSetOf()) { backupIdToName[it]?.let(nameToNewId::get) }
