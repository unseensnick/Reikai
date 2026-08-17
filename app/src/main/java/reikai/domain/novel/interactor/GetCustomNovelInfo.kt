package reikai.domain.novel.interactor

import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.Flow
import reikai.domain.novel.model.CustomNovelInfo
import reikai.domain.novel.repository.CustomNovelInfoRepository

@Inject
class GetCustomNovelInfo(
    private val repository: CustomNovelInfoRepository,
) {

    /** The override for one novel, re-emitting on every edit (drives the details overlay). */
    fun subscribe(novelId: Long): Flow<CustomNovelInfo?> = repository.getByNovelIdAsFlow(novelId)

    /** Every override, for the list surfaces (library/updates/history) to overlay by novel id. */
    fun subscribeAll(): Flow<List<CustomNovelInfo>> = repository.getAllAsFlow()
}
