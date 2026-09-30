package reikai.domain.track

import dev.zacsweers.metro.Inject
import eu.kanade.domain.track.interactor.RefreshTracks
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.model.TrackSearch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import reikai.domain.entry.EntryId
import reikai.domain.manga.DeleteTrackInGroup
import reikai.domain.manga.GetTracksInGroup
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.AddNovelTrack
import reikai.domain.novel.interactor.DeleteNovelTrack
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.RefreshNovelTracks
import reikai.domain.novel.model.NovelTrack
import reikai.domain.novel.track.NovelTrackUpdater
import reikai.domain.novel.track.toUiTrack
import reikai.domain.track.autobind.AutoBindEntry
import reikai.novel.source.NovelSourceManager
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.model.Track

/**
 * One content type's tracking engine, for one entry, as the tracking sheet reaches it: manga through
 * Mihon's `manga_track` interactors, novels through Reikai's `novel_tracks` ones. The reads and the
 * unbind span the merge group on both, so a track bound on a sibling source shows and clears here.
 */
interface EntryTrackPort {
    val writer: TrackWriter

    fun tracks(): Flow<List<Track>>

    suspend fun refresh(): List<Pair<Tracker?, Throwable>>

    /** Null while the entry or its source is not loaded. */
    suspend fun autoBindEntry(): AutoBindEntry?

    /** Whether [tracker]'s catalogue holds this type; binding across the two binds a different work. */
    fun supports(tracker: Tracker): Boolean

    suspend fun search(tracker: Tracker, query: String): List<TrackSearch>

    suspend fun bind(tracker: Tracker, item: TrackSearch)

    suspend fun unbindInGroup(trackerId: Long)
}

/** The one place an [EntryId] picks its tracking engine, so nothing above it branches on the type. */
@Inject
class EntryTrackPorts(
    private val getTracksInGroup: GetTracksInGroup,
    private val getManga: GetManga,
    private val sourceManager: SourceManager,
    private val refreshTracks: RefreshTracks,
    private val deleteTrackInGroup: DeleteTrackInGroup,
    private val getNovelTracks: GetNovelTracks,
    private val novelRepository: NovelRepository,
    private val novelSourceManager: NovelSourceManager,
    private val refreshNovelTracks: RefreshNovelTracks,
    private val addNovelTrack: AddNovelTrack,
    private val deleteNovelTrack: DeleteNovelTrack,
    private val novelTrackUpdater: NovelTrackUpdater,
) {

    fun of(entry: EntryId): EntryTrackPort = when (entry) {
        is EntryId.Manga -> MangaTrackPort(entry.rawId)
        is EntryId.Novel -> NovelTrackPort(entry.rawId)
    }

    private inner class MangaTrackPort(private val mangaId: Long) : EntryTrackPort {
        override val writer: TrackWriter = MangaTrackWriter

        override fun tracks() = getTracksInGroup.subscribe(mangaId)

        override suspend fun refresh() = refreshTracks.await(mangaId)

        override suspend fun autoBindEntry() =
            getManga.await(mangaId)?.let { AutoBindEntry.Manga(it, sourceManager.getOrStub(it.source)) }

        override fun supports(tracker: Tracker) = tracker.supportsContent(isNovel = false)

        override suspend fun search(tracker: Tracker, query: String) = tracker.search(query)

        override suspend fun bind(tracker: Tracker, item: TrackSearch) = tracker.register(item, mangaId)

        override suspend fun unbindInGroup(trackerId: Long) = deleteTrackInGroup.await(mangaId, trackerId)
    }

    private inner class NovelTrackPort(private val novelId: Long) : EntryTrackPort {
        override val writer: TrackWriter = novelTrackUpdater

        override fun tracks() = getNovelTracks.subscribeGroup(novelId).map { it.map(NovelTrack::toUiTrack) }

        override suspend fun refresh() = refreshNovelTracks.await(novelId)

        override suspend fun autoBindEntry() = novelRepository.getById(novelId)?.let { novel ->
            novelSourceManager.get(novel.source)?.let { AutoBindEntry.Novel(novel, it) }
        }

        override fun supports(tracker: Tracker) = tracker.supportsContent(isNovel = true)

        override suspend fun search(tracker: Tracker, query: String) = tracker.searchNovel(query)

        override suspend fun bind(tracker: Tracker, item: TrackSearch) {
            addNovelTrack.bind(tracker, item, novelId)
        }

        override suspend fun unbindInGroup(trackerId: Long) = deleteNovelTrack.awaitGroup(novelId, trackerId)
    }
}
