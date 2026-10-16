package reikai.presentation.stats

import androidx.compose.ui.util.fastFilter
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.track.Tracker
import eu.kanade.tachiyomi.data.track.TrackerManager
import kotlinx.coroutines.flow.first
import reikai.domain.category.GetNovelCategories
import reikai.domain.entry.EntryId
import reikai.domain.entry.GetEntryCustomInfo
import reikai.domain.library.ContentType
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.library.includes
import reikai.domain.library.mangaLibraryRanking
import reikai.domain.library.novelLibraryRanking
import reikai.domain.manga.MangaMergeManager
import reikai.domain.merge.MergedChapterUnitRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.NovelRepository
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.model.LibraryNovel
import reikai.domain.novel.track.toUiTrack
import reikai.domain.readinglog.EntryReadingSession
import reikai.domain.readinglog.ReadingLogRepository
import reikai.novel.source.NovelSourceManager
import reikai.presentation.library.SourceBadge
import reikai.presentation.library.badgeSource
import reikai.presentation.library.installedIconsBySite
import reikai.presentation.library.novelSourceBadge
import tachiyomi.domain.category.interactor.GetCategories
import tachiyomi.domain.library.model.LibraryManga
import tachiyomi.domain.manga.interactor.GetLibraryManga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.domain.track.interactor.GetTracksPerManga
import tachiyomi.domain.track.model.Track

/** Both content types' libraries as Statistics reads them, loaded once per screen. */
class StatsLibrary(
    val manga: StatsSeries<LibraryManga>,
    val novels: StatsSeries<LibraryNovel>,
    /** Every member's tracks on the logged-in trackers, so a tracker bound on any source counts. */
    val mangaTracks: Map<Long, List<Track>>,
    val novelTracks: Map<Long, List<Track>>,
    val loggedInTrackers: List<Tracker>,
    /** Every tracker's and category's name by id, for the groupings' rows and their library searches. */
    val trackerNames: Map<Long, String>,
    val categoryNames: Map<Long, String>,
    /** Each source's icon by its search key, drawn as the library's cover badges draw it. */
    val sourceBadges: Map<String, SourceBadge>,
    private val mangaTitles: List<StatsTitle>,
    private val novelTitles: List<StatsTitle>,
    private val mangaSessions: List<EntryReadingSession>,
    private val novelSessions: List<EntryReadingSession>,
) {
    fun titles(type: ContentType): List<StatsTitle> = buildList {
        if (type.includes(ContentType.MANGA)) addAll(mangaTitles)
        if (type.includes(ContentType.NOVELS)) addAll(novelTitles)
    }

    /** Logged sessions, each keyed to the title it counts under: a merged member's go to its series. */
    fun sessions(type: ContentType): List<TitleReadingSession> = buildList {
        if (type.includes(ContentType.MANGA)) addAll(mangaSessions.toTitleSessions(manga, mangaTitles, EntryId::Manga))
        if (type.includes(
                ContentType.NOVELS,
            )
        ) {
            addAll(novelSessions.toTitleSessions(novels, novelTitles, EntryId::Novel))
        }
    }

    private fun List<EntryReadingSession>.toTitleSessions(
        series: StatsSeries<*>,
        titles: List<StatsTitle>,
        entryId: (Long) -> EntryId,
    ): List<TitleReadingSession> {
        val titleByEntry = titles.associateBy { it.entryId }
        val titleIdByMember = series.titleIdByMember
        // A session on a series that has left the library has no title to count under.
        return mapNotNull { session ->
            val title = titleIdByMember[session.entryId]?.let { titleByEntry[entryId(it)] } ?: return@mapNotNull null
            TitleReadingSession(title, session)
        }
    }
}

data class TitleReadingSession(val title: StatsTitle, val session: EntryReadingSession)

@Inject
class StatsLibraryLoader(
    private val getLibraryManga: GetLibraryManga,
    private val novelRepository: NovelRepository,
    private val mangaMergeManager: MangaMergeManager,
    private val novelMergeManager: NovelMergeManager,
    private val mergedChapterUnits: MergedChapterUnitRepository,
    private val getTracksPerManga: GetTracksPerManga,
    private val getNovelTracks: GetNovelTracks,
    private val trackerManager: TrackerManager,
    private val readingLog: ReadingLogRepository,
    private val sourceManager: SourceManager,
    private val novelSourceManager: NovelSourceManager,
    private val getEntryCustomInfo: GetEntryCustomInfo,
    private val getCategories: GetCategories,
    private val getNovelCategories: GetNovelCategories,
    private val reikaiLibraryPreferences: ReikaiLibraryPreferences,
) {

    suspend fun load(): StatsLibrary {
        // A series favorited from three sources is three rows here and one card in the library, so
        // StatsSeries counts it once.
        // Each merged series ranked as its library row ranks it, so its status and categories are the lead's.
        val recognizedCounts = mergedChapterUnits.getRecognizedChapterCountsAsFlow().first()
        val preferredManga = reikaiLibraryPreferences.preferredMangaSources.get()
        val preferredNovels = reikaiLibraryPreferences.preferredNovelSources.get()
        val manga = mangaMergeManager.statsSeries(
            getLibraryManga.await(),
            mergedChapterUnits.getGroupCounts(ContentType.MANGA),
            rank = { members, ranking ->
                mangaLibraryRanking(members, ranking, preferredManga, recognizedCounts) { it.manga }
            },
        ) { it.id }
        val novels = novelMergeManager.statsSeries(
            novelRepository.getLibraryNovelAsFlow().first(),
            mergedChapterUnits.getGroupCounts(ContentType.NOVELS),
            rank = { members, ranking -> novelLibraryRanking(members, ranking, preferredNovels) },
        ) { it.id }
        val loggedInTrackers = trackerManager.loggedInTrackers()
        val trackers = loggedInTrackers.associateBy { it.id }
        val mangaTracks = getMangaTrackMap(manga.members, trackers.keys)
        val novelTracks = getNovelTrackMap(novels.members, trackers.keys)
        val mangaSessions = readingLog.sessions(ContentType.MANGA)
        val novelSessions = readingLog.sessions(ContentType.NOVELS)
        val customInfo = getEntryCustomInfo.awaitAll()

        val mangaSources = manga.members.map { it.manga.source }.distinct().associateWith { id ->
            sourceManager.getOrStub(id).let { StatsSource(id.toString(), it.name, it.lang) }
        }
        val novelSources = novels.members.map { it.novel.source }.distinct().associateWith { id ->
            novelSourceManager.identityOf(id).let { StatsSource(id, it.name, it.lang.orEmpty()) }
        }
        val iconsBySite = installedIconsBySite(novelSourceManager.getAll())
        val sourceBadges = mangaSources.keys.associate { id ->
            id.toString() to SourceBadge.Manga(sourceManager.badgeSource(id))
        } + novelSources.keys.associateWith { id -> novelSourceBadge(novelSourceManager.get(id), iconsBySite) }

        val mangaTitles = manga.statsTitles(
            StatsTitleFields(
                entryId = { EntryId.Manga(it.manga.id) },
                title = { customInfo[EntryId.Manga(it.manga.id)]?.title ?: it.manga.title },
                status = { it.manga.status },
                source = { mangaSources.getValue(it.manga.source) },
                genres = { it.manga.genre },
                customGenres = { customInfo[EntryId.Manga(it.manga.id)]?.genre },
                categoryIds = { it.categories },
                totalChapters = { it.totalChapters },
                readCount = { it.readCount },
            ),
            mangaTracks,
            trackers,
            readingLog.readDurationByEntry(ContentType.MANGA),
            firstLoggedRead(mangaSessions),
        )
        val novelTitles = novels.statsTitles(
            StatsTitleFields(
                entryId = { EntryId.Novel(it.novel.id) },
                title = { customInfo[EntryId.Novel(it.novel.id)]?.title ?: it.novel.title },
                status = { it.novel.status },
                source = { novelSources.getValue(it.novel.source) },
                genres = { it.novel.genre },
                customGenres = { customInfo[EntryId.Novel(it.novel.id)]?.genre },
                categoryIds = { it.categories },
                totalChapters = { it.totalChapters },
                readCount = { it.readCount },
            ),
            novelTracks,
            trackers,
            readingLog.readDurationByEntry(ContentType.NOVELS),
            firstLoggedRead(novelSessions),
        )

        return StatsLibrary(
            manga = manga,
            novels = novels,
            mangaTracks = mangaTracks,
            novelTracks = novelTracks,
            loggedInTrackers = loggedInTrackers,
            trackerNames = trackerManager.trackers.associate { it.id to it.name },
            // One table holds both types' categories; each getter adds the universal ones.
            categoryNames = (getCategories.await() + getNovelCategories.await()).associate { it.id to it.name },
            sourceBadges = sourceBadges,
            mangaTitles = mangaTitles,
            novelTitles = novelTitles,
            mangaSessions = mangaSessions,
            novelSessions = novelSessions,
        )
    }

    private suspend fun getMangaTrackMap(
        libraryManga: List<LibraryManga>,
        loggedInTrackerIds: Set<Long>,
    ): Map<Long, List<Track>> {
        if (loggedInTrackerIds.isEmpty()) return emptyMap()
        val tracksPerManga = getTracksPerManga.await()
        return libraryManga.associate { manga ->
            manga.id to tracksPerManga[manga.id].orEmpty().fastFilter { it.trackerId in loggedInTrackerIds }
        }
    }

    // Each novel track converted to the shared Track (toUiTrack), so the scoring kernels serve both types.
    private suspend fun getNovelTrackMap(
        libraryNovels: List<LibraryNovel>,
        loggedInTrackerIds: Set<Long>,
    ): Map<Long, List<Track>> {
        if (loggedInTrackerIds.isEmpty()) return emptyMap()
        val tracksPerNovel = getNovelTracks.awaitAll()
        return libraryNovels.associate { novel ->
            novel.id to tracksPerNovel[novel.id].orEmpty().map { it.toUiTrack() }
                .fastFilter { it.trackerId in loggedInTrackerIds }
        }
    }
}
