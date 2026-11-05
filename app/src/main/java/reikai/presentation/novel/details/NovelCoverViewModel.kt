package reikai.presentation.novel.details

import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.saver.ImageSaver
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import reikai.domain.entry.ClearCustomCover
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.UpdateNovel
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.NovelUpdate
import reikai.domain.novel.model.asNovelCover
import reikai.domain.novel.model.hasCustomCover
import reikai.domain.novel.model.withCustomInfo
import reikai.novel.local.LocalNovelSource
import reikai.presentation.details.EntryCoverViewModel
import java.io.InputStream

/**
 * The novel cover source for the shared [EntryCoverViewModel]. Subscribes by url + source (there is no
 * by-id novel flow) and keys the custom cover by [EntryId.Novel] (so it can't collide with a same-id
 * manga). The save / share machinery lives in the shared base.
 */
@AssistedInject
class NovelCoverViewModel(
    @Assisted private val novelUrl: String,
    @Assisted private val novelSource: String,
    private val novelRepo: NovelRepository,
    private val getCustomNovelInfo: GetCustomNovelInfo,
    private val updateNovel: UpdateNovel,
    private val coverCache: CoverCache,
    private val clearCustomCover: ClearCustomCover,
    private val localNovelSource: LocalNovelSource,
    imageSaver: ImageSaver,
) : EntryCoverViewModel<Novel>(imageSaver) {

    @AssistedFactory
    fun interface Factory {
        fun create(novelUrl: String, novelSource: String): NovelCoverViewModel
    }

    // Overlaid with the edit-info cover URL, matching the manga twin: the header renders it, so
    // the viewer and Save/Share must show the same image. A custom cover file still wins.
    override suspend fun subscribe(): Flow<Novel?> =
        novelRepo.getByUrlAndSourceAsFlow(novelUrl, novelSource).flatMapLatest { novel ->
            if (novel == null) flowOf(null) else getCustomNovelInfo.subscribe(novel.id).map(novel::withCustomInfo)
        }

    override fun coilModel(entry: Novel): Any = entry.asNovelCover()

    override fun coverName(entry: Novel): String = entry.title

    // As manga's: a local novel's cover goes into its own folder, so it needs no library entry to keep it.
    override fun canEditCover(entry: Novel): Boolean = entry.favorite || LocalNovelSource.isLocal(entry.source)

    override fun hasCustomCover(): Boolean = entry.value?.hasCustomCover(coverCache) ?: false

    override suspend fun persistCustomCover(entry: Novel, stream: InputStream) {
        val isLocal = LocalNovelSource.isLocal(entry.source)
        val inFolder = if (isLocal) localNovelSource.writeCover(entry.url, stream) else null
        // The stored address may be the book's own picture, copied out, which the folder's cover now replaces.
        if (inFolder != null) {
            novelRepo.update(NovelUpdate(entry.id) { thumbnailUrl = inFolder })
        } else {
            coverCache.setCustomCoverToCache(EntryId.Novel(entry.id), stream)
        }
        updateNovel.awaitUpdateCoverLastModified(entry.id)
    }

    override suspend fun removeCustomCover(entry: Novel) {
        clearCustomCover.await(EntryId.Novel(entry.id))
    }
}
