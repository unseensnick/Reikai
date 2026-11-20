package reikai.presentation.notes

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactory
import dev.zacsweers.metrox.viewmodel.ManualViewModelAssistedFactoryKey
import dev.zacsweers.metrox.viewmodel.assistedMetroViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.Serializable
import mihon.core.metro.AppCoroutineScope
import reikai.domain.chapter.LocalVolumeRepository
import reikai.domain.chapter.savedVolume
import reikai.domain.library.ContentType
import reikai.domain.novel.NovelChapterRepository
import reikai.domain.novel.NovelRepository
import reikai.novel.local.LocalNovelSource
import reikai.presentation.details.ChapterVolume
import reikai.presentation.details.EntryChapterListItem
import reikai.presentation.details.chipOf
import reikai.presentation.details.mangaChapterVolume
import reikai.presentation.details.novelChapterVolume
import reikai.presentation.details.volumeName
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.manga.interactor.GetMangaByUrlAndSourceId
import tachiyomi.source.local.LocalSource

/**
 * The notes of one volume of a local series, either content type, which its page shows while that volume's
 * chip is picked. Keyed as the volume's saved order and name are, so they follow it through a move.
 */
@Serializable
data class VolumeNotesRoute(
    val type: ContentType,
    val series: String,
    val volumeKey: String,
) : NavKey {

    // Not private: a graph-contributed factory has to be visible to the generated graph code.
    @AssistedInject
    class Model(
        @Assisted private val type: ContentType,
        @Assisted private val series: String,
        @Assisted private val volumeKey: String,
        @AppCoroutineScope appScope: CoroutineScope,
        private val volumes: LocalVolumeRepository,
        private val getMangaByUrlAndSourceId: GetMangaByUrlAndSourceId,
        private val getChaptersByMangaId: GetChaptersByMangaId,
        private val novels: NovelRepository,
        private val novelChapters: NovelChapterRepository,
    ) : ViewModel() {

        /** The volume's notes, headed by its chip; an empty note is a volume with none written yet. */
        val notes = NotesEditor(
            viewModelScope,
            appScope,
            load = ::load,
            write = { content -> volumes.setNotes(type, series, volumeKey, content) },
        )

        private suspend fun load(): Pair<EntryChapterListItem.Volume, String>? {
            val saved = volumes.loadedVolumes(type)
            val chip = seriesVolumes()?.chipOf(series, volumeKey, saved, volumes.loadedLooseFiles()) ?: return null
            return chip to saved.savedVolume(series, volumeKey)?.notes.orEmpty()
        }

        // The series' stored chapters, as its details page reads them; null once the series is gone.
        private suspend fun seriesVolumes(): List<ChapterVolume>? = when (type) {
            ContentType.MANGA -> getMangaByUrlAndSourceId.await(series, LocalSource.ID)?.let { manga ->
                getChaptersByMangaId.await(manga.id).mapNotNull { mangaChapterVolume(manga, it.url, it.name) }
            }
            ContentType.NOVELS -> novels.getByUrlAndSource(series, LocalNovelSource.ID)?.let { novel ->
                novelChapters.getByNovelId(novel.id).mapNotNull { novelChapterVolume(novel.url, it.url, it.name) }
            }
            ContentType.ALL -> null
        }

        @AssistedFactory
        @ManualViewModelAssistedFactoryKey
        @ContributesIntoMap(AppScope::class)
        interface Factory : ManualViewModelAssistedFactory {
            fun create(type: ContentType, series: String, volumeKey: String): Model
        }
    }
}

@Composable
fun VolumeNotesScreen(type: ContentType, series: String, volumeKey: String) {
    val viewModel = assistedMetroViewModel<VolumeNotesRoute.Model, VolumeNotesRoute.Model.Factory> {
        create(type = type, series = series, volumeKey = volumeKey)
    }

    EntryNotesScreen(editor = viewModel.notes, subtitle = { volumeName(it) }, series = null)
}
