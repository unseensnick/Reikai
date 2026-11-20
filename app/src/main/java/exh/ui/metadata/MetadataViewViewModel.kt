package exh.ui.metadata

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.source.online.MetadataSource
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.getMainSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import reikai.data.coil.extractCoverColor
import reikai.data.coil.lastSeedColor
import reikai.data.coil.seedColor
import reikai.domain.entry.EntryId
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.asMangaCover
import tachiyomi.domain.source.service.SourceManager

@AssistedInject
class MetadataViewViewModel(
    @Assisted val mangaId: Long,
    @Assisted val sourceId: Long,
    private val context: Context,
    private val getFlatMetadataById: GetFlatMetadataById,
    private val sourceManager: SourceManager,
    private val getManga: GetManga,
    uiPreferences: UiPreferences,
) : ViewModel() {

    @AssistedFactory
    @ManualViewModelAssistedFactoryKey
    @ContributesIntoMap(AppScope::class)
    interface Factory : ManualViewModelAssistedFactory {
        fun create(mangaId: Long, sourceId: Long): MetadataViewViewModel
    }

    val state: StateFlow<MetadataViewState>
        field = MutableStateFlow<MetadataViewState>(MetadataViewState.Loading)

    private val themeCoverBased = uiPreferences.themeCoverBased.get()

    private val _manga = MutableStateFlow<Manga?>(null)
    val manga = _manga.asStateFlow()

    /** The colour the manga's cover tints the page with, when the cover theme is on, from the first frame. */
    val seedColor: StateFlow<Int?>
        field = MutableStateFlow(EntryId.Manga(mangaId).lastSeedColor()?.takeIf { themeCoverBased })

    init {
        viewModelScope.launchIO {
            val manga = getManga.await(mangaId)
            _manga.value = manga
            if (manga != null && themeCoverBased) {
                val cover = manga.asMangaCover()
                seedColor.value = EntryId.Manga(manga.id).seedColor(cover.url, cover.lastModified, manga.favorite) {
                    context.extractCoverColor(cover)
                }
            }
        }

        viewModelScope.launchIO {
            val metadataSource = sourceManager.get(sourceId)?.getMainSource<MetadataSource<*, *>>()
            if (metadataSource == null) {
                state.value = MetadataViewState.SourceNotFound
                return@launchIO
            }

            state.value = when (val flatMetadata = getFlatMetadataById.await(mangaId)) {
                null -> MetadataViewState.MetadataNotFound
                else -> MetadataViewState.Success(flatMetadata.raise(metadataSource.metaClass))
            }
        }
    }
}

sealed class MetadataViewState {
    data object Loading : MetadataViewState()
    data class Success(val meta: RaisedSearchMetadata) : MetadataViewState()
    data object MetadataNotFound : MetadataViewState()
    data object SourceNotFound : MetadataViewState()
}
