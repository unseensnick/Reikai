package reikai.domain.chapter

import kotlinx.coroutines.flow.MutableStateFlow
import reikai.domain.library.ContentType

/** No volume ordered or named, for a test whose subject is not local volumes. */
object NoLocalVolumes : LocalVolumeRepository {
    override fun volumes(type: ContentType) = MutableStateFlow(emptyMap<String, List<LocalVolume>>())

    override suspend fun setOrder(type: ContentType, series: String, keys: List<String>) = Unit

    override suspend fun setLabel(type: ContentType, series: String, key: String, label: String?) = Unit

    override suspend fun restore(type: ContentType, series: String, volumes: List<LocalVolume>) = Unit
}
