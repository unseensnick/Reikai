package reikai.domain.entry

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.SetCustomNovelInfo
import reikai.domain.novel.model.CustomNovelInfo
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.SetCustomMangaInfo
import tachiyomi.domain.manga.model.CustomMangaInfo

/** Sets only an entry's Edit info title, keeping its other overrides, for both content types. */
@Inject
class SetEntryTitle(
    private val getCustomMangaInfo: GetCustomMangaInfo,
    private val setCustomMangaInfo: SetCustomMangaInfo,
    private val getCustomNovelInfo: GetCustomNovelInfo,
    private val setCustomNovelInfo: SetCustomNovelInfo,
) {

    /** Sets [id]'s title to [title], or back to the source's when null, answering the title it had before. */
    suspend fun await(id: EntryId, title: String?): String? = when (id) {
        is EntryId.Manga -> {
            val current = getCustomMangaInfo.subscribe(id.rawId).first() ?: CustomMangaInfo(id.rawId)
            setCustomMangaInfo.set(current.copy(title = title))
            current.title
        }
        is EntryId.Novel -> {
            val current = getCustomNovelInfo.subscribe(id.rawId).first() ?: CustomNovelInfo(id.rawId)
            setCustomNovelInfo.set(current.copy(title = title))
            current.title
        }
    }
}
