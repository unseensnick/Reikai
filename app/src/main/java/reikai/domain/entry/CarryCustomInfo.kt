package reikai.domain.entry

import dev.zacsweers.metro.Inject
import reikai.domain.novel.interactor.SetCustomNovelInfo
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo

/**
 * A migration's custom info carry, one rule for both content types: the source's whole Edit info row
 * (alternative titles included) replaces the target's; a source with none leaves the target's alone,
 * as a restore does. The custom cover is a file, carried by its own option.
 */
@Inject
class CarryCustomInfo(
    private val getEntryCustomInfo: GetEntryCustomInfo,
    private val setCustomMangaInfo: SetCustomMangaInfo,
    private val setCustomNovelInfo: SetCustomNovelInfo,
) {

    suspend fun await(from: EntryId, to: EntryId) {
        val info = getEntryCustomInfo.await(from) ?: return
        when (to) {
            is EntryId.Manga -> setCustomMangaInfo.set(info.toCustomMangaInfo(to.rawId))
            is EntryId.Novel -> setCustomNovelInfo.set(info.toCustomNovelInfo(to.rawId))
        }
    }
}
