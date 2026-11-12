package reikai.domain.chapter

import kotlinx.coroutines.flow.MutableStateFlow
import reikai.domain.library.ContentType

/** No volume chip picked, for a test whose subject is not volume picks. */
object NoVolumePicks : VolumePickRepository {
    override fun picks(type: ContentType) = MutableStateFlow(emptyMap<Long, String>())

    override suspend fun set(type: ContentType, entryId: Long, volumeKey: String?) = Unit

    override suspend fun loadedPicks(type: ContentType) = emptyMap<Long, String>()

    override suspend fun restore(type: ContentType, series: String, key: String) = Unit
}
