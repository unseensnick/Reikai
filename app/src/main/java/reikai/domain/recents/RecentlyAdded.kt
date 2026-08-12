package reikai.domain.recents

import reikai.data.coil.NovelCover
import tachiyomi.domain.manga.model.MangaCover

/**
 * A library entry that was recently added, one row per content type. Deliberately not modelled on
 * `UpdatesWithRelations` or `NovelUpdateWithRelations`: those are chapter-centric and make the
 * chapter's id, name, read flag and progress non-null, none of which a newly-added entry has. The
 * recents adapters map these into the neutral item; nothing else reads them.
 */
data class RecentlyAddedManga(
    val mangaId: Long,
    val title: String,
    val dateAdded: Long,
    val coverData: MangaCover,
)

/**
 * Novel twin. Carries no source or url, though the novel details screen is keyed by that pair: a row
 * only ever reaches it through the adapter, which is handed an entry id and looks the novel up.
 */
data class RecentlyAddedNovel(
    val novelId: Long,
    val title: String,
    val dateAdded: Long,
    val coverData: NovelCover,
)
