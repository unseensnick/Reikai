package reikai.presentation.novel.details

import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.saver.ImageSaver
import kotlinx.coroutines.flow.Flow
import reikai.data.coil.NovelCover
import reikai.domain.entry.EntryId
import reikai.domain.entry.coverCacheKey
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.presentation.details.EntryCoverScreenModel
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.InputStream

/**
 * The novel cover source for the shared [EntryCoverScreenModel]. Subscribes by url + source (there is no
 * by-id novel flow) and keys the custom cover by the negated novel id (so it can't collide with a same-id
 * manga). The save / share machinery lives in the shared base.
 */
class NovelCoverScreenModel(
    private val novelUrl: String,
    private val novelSource: String,
    private val site: String?,
    private val novelRepo: NovelRepository = Injekt.get(),
    private val updateNovel: UpdateNovel = Injekt.get(),
    private val coverCache: CoverCache = Injekt.get(),
    imageSaver: ImageSaver = Injekt.get(),
) : EntryCoverScreenModel<Novel>(imageSaver) {

    override suspend fun subscribe(): Flow<Novel?> = novelRepo.getByUrlAndSourceAsFlow(novelUrl, novelSource)

    override fun coilModel(entry: Novel): Any = entry.toNovelCover()

    override fun coverName(entry: Novel): String = entry.title

    override fun hasCustomCover(): Boolean {
        val novel = entry.value ?: return false
        return coverCache.getCustomCoverFile(EntryId.Novel(novel.id).coverCacheKey()).exists()
    }

    override suspend fun persistCustomCover(entry: Novel, stream: InputStream) {
        coverCache.getCustomCoverFile(EntryId.Novel(entry.id).coverCacheKey())
            .outputStream().use { output -> stream.copyTo(output) }
        updateNovel.awaitUpdateCoverLastModified(entry.id)
    }

    override suspend fun removeCustomCover(entry: Novel) {
        coverCache.deleteCustomCover(EntryId.Novel(entry.id).coverCacheKey())
        updateNovel.awaitUpdateCoverLastModified(entry.id)
    }

    private fun Novel.toNovelCover() = NovelCover(
        url = thumbnailUrl,
        site = site,
        isNovelFavorite = favorite,
        lastModified = coverLastModified,
        novelId = id,
    )
}
