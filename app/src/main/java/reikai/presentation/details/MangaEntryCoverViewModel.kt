package reikai.presentation.details

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
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream

/**
 * The manga cover source for the shared [EntryCoverViewModel]. Keyed by the positive manga id; custom
 * covers write through the standard `Manga.editCover` (local source or favorite only). Replaces Mihon's
 * `MangaEntryCoverViewModel`, whose save / share machinery now lives in the shared base.
 */
class MangaEntryCoverViewModel(
    private val mangaId: Long,
    private val getManga: GetManga = Injekt.get(),
    private val getCustomMangaInfo: GetCustomMangaInfo = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val coverManager: LocalCoverManager = Injekt.get(),
    imageSaver: ImageSaver = Injekt.get(),
) : EntryCoverViewModel<Manga>(imageSaver) {

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
