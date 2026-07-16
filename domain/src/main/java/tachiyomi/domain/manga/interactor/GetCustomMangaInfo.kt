package tachiyomi.domain.manga.interactor

import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.model.CustomMangaInfo
import tachiyomi.domain.manga.repository.CustomMangaInfoRepository

class GetCustomMangaInfo(
    private val repository: CustomMangaInfoRepository,
) {

    /** The override for one manga, re-emitting on every edit (drives the details overlay). */
    fun subscribe(mangaId: Long): Flow<CustomMangaInfo?> = repository.getByMangaIdAsFlow(mangaId)

    /** Every override, for the list surfaces (library/updates/history) to overlay by manga id. */
    fun subscribeAll(): Flow<List<CustomMangaInfo>> = repository.getAllAsFlow()
}
