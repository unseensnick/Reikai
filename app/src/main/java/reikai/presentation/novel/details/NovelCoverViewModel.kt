package reikai.presentation.novel.details

import dev.zacsweers.metro.Assisted
import dev.zacsweers.metro.AssistedFactory
import dev.zacsweers.metro.AssistedInject
import eu.kanade.tachiyomi.data.cache.CoverCache
import eu.kanade.tachiyomi.data.saver.ImageSaver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import mihon.core.metro.AppCoroutineScope
import reikai.domain.entry.ClearCustomCover
import reikai.domain.entry.EntryId
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetCustomNovelInfo
import reikai.domain.novel.interactor.SetNovelCustomCover
import reikai.domain.novel.model.Novel
import reikai.domain.novel.model.asNovelCover
import reikai.domain.novel.model.hasCustomCover
import reikai.domain.novel.model.withCustomInfo
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
    private val coverCache: CoverCache,
    private val clearCustomCover: ClearCustomCover,
    private val setNovelCustomCover: SetNovelCustomCover,
    imageSaver: ImageSaver,
    @AppCoroutineScope appScope: CoroutineScope,
) : EntryCoverViewModel<Novel>(imageSaver, appScope) {

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

    override fun canEditCover(entry: Novel): Boolean = SetNovelCustomCover.canSet(entry)

    override fun hasCustomCover(): Boolean = entry.value?.hasCustomCover(coverCache) ?: false

    override suspend fun persistCustomCover(entry: Novel, stream: InputStream) = setNovelCustomCover.await(
        entry,
        stream,
    )

    override suspend fun removeCustomCover(entry: Novel) {
        clearCustomCover.await(EntryId.Novel(entry.id))
    }
}
