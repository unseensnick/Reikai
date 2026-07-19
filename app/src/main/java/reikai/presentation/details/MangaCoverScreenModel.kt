package reikai.presentation.details

import eu.kanade.domain.manga.interactor.UpdateManga
import eu.kanade.domain.manga.model.hasCustomCover
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.saver.ImageSaver
import eu.kanade.tachiyomi.util.editCover
import kotlinx.coroutines.flow.Flow
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.source.local.image.LocalCoverManager
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream

/**
 * The manga cover source for the shared [EntryCoverScreenModel]. Keyed by the positive manga id; custom
 * covers write through the standard `Manga.editCover` (local source or favorite only). Replaces Mihon's
 * `MangaCoverScreenModel`, whose save / share machinery now lives in the shared base.
 */
class MangaCoverScreenModel(
    private val mangaId: Long,
    private val getManga: GetManga = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val coverManager: LocalCoverManager = Injekt.get(),
    imageSaver: ImageSaver = Injekt.get(),
) : EntryCoverScreenModel<Manga>(imageSaver) {

    override suspend fun subscribe(): Flow<Manga?> = getManga.subscribe(mangaId)

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
