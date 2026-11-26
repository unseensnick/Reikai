package reikai.domain.chapter

import kotlinx.coroutines.flow.MutableStateFlow
import reikai.domain.library.StoredType
import reikai.domain.local.VolumeOwner

/** No volume ordered or named, for a test whose subject is not local volumes. */
object NoLocalVolumes : LocalVolumeRepository {
    override fun volumes(type: StoredType) = MutableStateFlow(emptyMap<String, List<LocalVolume>>())

    override suspend fun loadedVolumes(type: StoredType) = emptyMap<String, List<LocalVolume>>()

    override suspend fun setOrder(type: StoredType, series: String, keys: List<String>) = Unit

    override suspend fun setLabel(type: StoredType, series: String, key: String, label: String?) = Unit

    override suspend fun setNotes(type: StoredType, series: String, key: String, notes: String) = Unit

    override suspend fun restore(type: StoredType, series: String, volumes: List<LocalVolume>) = Unit

    override suspend fun all(type: StoredType) = emptyList<VolumeOwner>()

    override fun looseFiles() = MutableStateFlow(emptyMap<String, LooseFiles>())

    override suspend fun loadedLooseFiles() = emptyMap<String, LooseFiles>()

    override suspend fun setLooseFiles(series: String, mode: LooseFiles) = Unit
}
