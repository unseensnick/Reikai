package exh.ui.metadata

import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import eu.kanade.domain.ui.UiPreferences
import eu.kanade.tachiyomi.source.online.MetadataSource
import exh.metadata.metadata.RaisedSearchMetadata
import exh.source.getMainSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import mihon.core.viewmodel.StateViewModel
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

class MetadataViewViewModel(
    val mangaId: Long,
    val sourceId: Long,
    private val getFlatMetadataById: GetFlatMetadataById = Injekt.get(),
    private val sourceManager: SourceManager = Injekt.get(),
    private val getManga: GetManga = Injekt.get(),
) : StateViewModel<MetadataViewState>(MetadataViewState.Loading) {

    private val uiPreferences = Injekt.get<UiPreferences>()
    val themeCoverBased = uiPreferences.themeCoverBased.get()

    private val _manga = MutableStateFlow<Manga?>(null)
    val manga = _manga.asStateFlow()

    init {
        viewModelScope.launchIO {
            _manga.value = getManga.await(mangaId)
        }

        viewModelScope.launchIO {
            val metadataSource = sourceManager.get(sourceId)?.getMainSource<MetadataSource<*, *>>()
            if (metadataSource == null) {
                mutableState.value = MetadataViewState.SourceNotFound
                return@launchIO
            }

            mutableState.value = when (val flatMetadata = getFlatMetadataById.await(mangaId)) {
                null -> MetadataViewState.MetadataNotFound
                else -> MetadataViewState.Success(flatMetadata.raise(metadataSource.metaClass))
            }
        }
    }

    companion object {
        val MANGA_ID_KEY = CreationExtras.Key<Long>()
        val SOURCE_ID_KEY = CreationExtras.Key<Long>()

        val Factory = viewModelFactory {
            initializer {
                MetadataViewViewModel(
                    mangaId = get(MANGA_ID_KEY)!!,
                    sourceId = get(SOURCE_ID_KEY)!!,
                )
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
