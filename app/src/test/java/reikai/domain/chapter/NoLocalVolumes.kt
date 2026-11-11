package reikai.domain.chapter

import kotlinx.coroutines.flow.MutableStateFlow
import reikai.domain.library.ContentType
import reikai.domain.local.VolumeOwner

/** No volume ordered or named, for a test whose subject is not local volumes. */
object NoLocalVolumes : LocalVolumeRepository {
    override fun volumes(type: ContentType) = MutableStateFlow(emptyMap<String, List<LocalVolume>>())

    override suspend fun loadedVolumes(type: ContentType) = emptyMap<String, List<LocalVolume>>()

    override suspend fun setOrder(type: ContentType, series: String, keys: List<String>) = Unit

    override suspend fun setLabel(type: ContentType, series: String, key: String, label: String?) = Unit

    override suspend fun restore(type: ContentType, series: String, volumes: List<LocalVolume>) = Unit

    override suspend fun all(type: ContentType) = emptyList<VolumeOwner>()

    override suspend fun restoreOwners(type: ContentType, owners: List<VolumeOwner>) = Unit

    override suspend fun loadedPicks(type: ContentType) = emptyMap<Long, String>()

    override suspend fun restorePick(type: ContentType, series: String, key: String) = Unit

    override fun looseFiles() = MutableStateFlow(emptyMap<String, LooseFiles>())

    override suspend fun loadedLooseFiles() = emptyMap<String, LooseFiles>()

    override suspend fun setLooseFiles(series: String, mode: LooseFiles) = Unit
}
