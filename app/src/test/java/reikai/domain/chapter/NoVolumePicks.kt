package reikai.domain.chapter

import kotlinx.coroutines.flow.MutableStateFlow
import reikai.domain.library.StoredType

/** No volume chip picked, for a test whose subject is not volume picks. */
object NoVolumePicks : VolumePickRepository {
    override fun picks(type: StoredType) = MutableStateFlow(emptyMap<Long, String>())

    override suspend fun set(type: StoredType, entryId: Long, volumeKey: String?) = Unit

    override suspend fun loadedPicks(type: StoredType) = emptyMap<Long, String>()

    override suspend fun restore(type: StoredType, series: String, key: String) = Unit
}
