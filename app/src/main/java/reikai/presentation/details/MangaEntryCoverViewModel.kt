package reikai.presentation.details

import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.util.editCover
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import tachiyomi.domain.manga.interactor.GetCustomMangaInfo
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.manga.model.withCustomInfo
import tachiyomi.source.local.image.LocalCoverManager
import java.io.InputStream

/**
 * The manga cover source for the shared [EntryCoverViewModel]. Keyed by the positive manga id; custom
 * covers write through the standard `Manga.editCover` (local source or favorite only). Replaces Mihon's
 * `MangaEntryCoverViewModel`, whose save / share machinery now lives in the shared base.
 */
@AssistedInject
class MangaEntryCoverViewModel(
    @Assisted private val mangaId: Long,
    private val getManga: GetManga,
    private val getCustomMangaInfo: GetCustomMangaInfo,
    private val coverCache: CoverCache,
    private val updateManga: UpdateManga,
    private val coverManager: LocalCoverManager,
    imageSaver: ImageSaver,
) : EntryCoverViewModel<Manga>(imageSaver) {

    @AssistedFactory
    fun interface Factory {
        fun create(mangaId: Long): MangaEntryCoverViewModel
    }

    // Overlaid with the edit-info cover URL: the header renders it, so the full-screen viewer and
    // Save/Share must show the same image. A custom cover FILE still wins inside the fetcher.
    override suspend fun subscribe(): Flow<Manga?> =
        getManga.subscribe(mangaId).combine(getCustomMangaInfo.subscribe(mangaId)) { manga, custom ->
            manga?.withCustomInfo(custom)
        }

    override fun coilModel(entry: Manga): Any = entry

    override fun coverName(entry: Manga): String = entry.title

    override fun hasCustomCover(): Boolean = entry.value?.hasCustomCover(coverCache) ?: false

    override suspend fun persistCustomCover(entry: Manga, stream: InputStream) {
        entry.editCover(coverManager, stream, updateManga, coverCache)
    }

    override suspend fun removeCustomCover(entry: Manga) {
        coverCache.deleteCustomCover(entry.id)
        updateManga.awaitUpdateCoverLastModified(entry.id)
    }
}
