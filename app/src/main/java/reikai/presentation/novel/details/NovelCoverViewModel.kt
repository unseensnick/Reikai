package reikai.presentation.novel.details

import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.saver.ImageSaver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import reikai.data.coil.NovelCover
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.withCustomInfo
import reikai.presentation.details.EntryCoverViewModel
import java.io.InputStream

/**
 * The novel cover source for the shared [EntryCoverViewModel]. Subscribes by url + source (there is no
 * by-id novel flow) and keys the custom cover by the negated novel id (so it can't collide with a same-id
 * manga). The save / share machinery lives in the shared base.
 */
@AssistedInject
class NovelCoverViewModel(
    @Assisted private val novelUrl: String,
    @Assisted private val novelSource: String,
    @Assisted private val site: String?,
    private val novelRepo: NovelRepository,
    private val getCustomNovelInfo: GetCustomNovelInfo,
    private val updateNovel: UpdateNovel,
    private val coverCache: CoverCache,
    imageSaver: ImageSaver,
) : EntryCoverViewModel<Novel>(imageSaver) {

    @AssistedFactory
    fun interface Factory {
        fun create(novelUrl: String, novelSource: String, site: String?): NovelCoverViewModel
    }

    // Overlaid with the edit-info cover URL, matching the manga twin: the header renders it, so
    // the viewer and Save/Share must show the same image. A custom cover file still wins.
    override suspend fun subscribe(): Flow<Novel?> =
        novelRepo.getByUrlAndSourceAsFlow(novelUrl, novelSource)
            .combine(getCustomNovelInfo.subscribeAll()) { novel, custom ->
                novel?.withCustomInfo(custom.firstOrNull { it.novelId == novel.id })
            }

    override fun coilModel(entry: Novel): Any = entry.toNovelCover()

    override fun coverName(entry: Novel): String = entry.title

    override fun hasCustomCover(): Boolean {
        val novel = entry.value ?: return false
        return coverCache.getCustomCoverFile(EntryId.Novel(novel.id)).exists()
    }

    override suspend fun persistCustomCover(entry: Novel, stream: InputStream) {
        coverCache.getCustomCoverFile(EntryId.Novel(entry.id))
            .outputStream().use { output -> stream.copyTo(output) }
        updateNovel.awaitUpdateCoverLastModified(entry.id)
    }

    override suspend fun removeCustomCover(entry: Novel) {
        coverCache.deleteCustomCover(EntryId.Novel(entry.id))
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
