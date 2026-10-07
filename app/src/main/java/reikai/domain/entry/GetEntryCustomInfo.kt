package reikai.domain.entry

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import reikai.domain.novel.interactor.GetCustomNovelInfo
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo

/**
 * Every Edit info override of both content types, keyed by [EntryId] since a manga and a novel can
 * share a row id. For the surfaces that name entries of either type outside their own feed.
 */
@Inject
class GetEntryCustomInfo(
    private val getCustomMangaInfo: GetCustomMangaInfo,
    private val getCustomNovelInfo: GetCustomNovelInfo,
) {

    fun subscribeAll(): Flow<Map<EntryId, EntryCustomInfo>> = combine(
        getCustomMangaInfo.subscribeAll(),
        getCustomNovelInfo.subscribeAll(),
    ) { manga, novels ->
        manga.associateBy<_, EntryId> { EntryId.Manga(it.mangaId) } +
            novels.associateBy { EntryId.Novel(it.novelId) }
    }

    suspend fun awaitAll(): Map<EntryId, EntryCustomInfo> = subscribeAll().first()

    /** [entries] under their overrides, [id] typing each one's row id. */
    suspend fun <T> overlay(entries: List<T>, id: (T) -> EntryId, apply: T.(EntryCustomInfo?) -> T): List<T> {
        if (entries.isEmpty()) return entries
        val custom = awaitAll()
        return entries.map { it.apply(custom[id(it)]) }
    }

    /** One entry's overrides, for a surface naming a single entry. */
    suspend fun await(id: EntryId): EntryCustomInfo? = when (id) {
        is EntryId.Manga -> getCustomMangaInfo.subscribe(id.rawId).first()
        is EntryId.Novel -> getCustomNovelInfo.subscribe(id.rawId).first()
    }
}
