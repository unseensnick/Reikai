package eu.kanade.tachiyomi.data.recommendation

import co.touchlab.kermit.Logger
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.ui.manga.related.RelatedMangaCandidate
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import uy.kohesive.injekt.injectLazy
import yokai.domain.library.taste.interactor.ComputeTasteProfile
import yokai.domain.library.taste.interactor.GetLibraryStatuses
import yokai.domain.library.taste.interactor.GetTrackedEntries
import yokai.domain.library.taste.model.TasteProfile
import yokai.domain.library.taste.model.TrackStatus
import yokai.domain.manga.interactor.GetManga

/** One progressive view of the related-mangas pool: the carousel slice, the full pool, and whether
 *  a fetch is still in flight. Emitted repeatedly as streams complete. */
data class RelatedMangasResult(
    val carousel: List<RelatedMangaCandidate>,
    val fullPool: List<RelatedMangaCandidate>,
    val loading: Boolean,
)

/**
 * Builds the related/suggested-mangas pool for the details carousel, shared by the legacy
 * [eu.kanade.tachiyomi.ui.manga.MangaDetailsPresenter] and the Compose
 * [yokai.presentation.details.manga.MangaDetailsScreenModel]. Both collect [load]'s Flow and map
 * each emission onto their own UI state.
 *
 * Three input streams feed one pool: source-native related mangas (`getRelatedMangaList`), the
 * keyword-search fallback, and tracker-backed recommendations (AniList / MyAnimeList via Jikan /
 * MangaUpdates). Tracker batches are tagged with [RECOMMENDS_SOURCE] so a tap can route through
 * Global Search instead of resolving a tracker URL against the current source. Dedup is by SManga
 * url within the pool plus a normalized-title key spanning streams (tracker and source URLs live in
 * distinct namespaces). The 30-cap carousel slice reserves [RELATED_MANGAS_TRACKER_RESERVE] slots
 * for trackers; the unbounded pool feeds "See all".
 *
 * The first emission may be a cached pool (instant reopen); a fresh cache entry ends the Flow
 * there, a stale one keeps the cards visible while the three streams refresh in the background.
 */
class RelatedMangasLoader {
    private val relatedMangaCache: RelatedMangaCache by injectLazy()
    private val getManga: GetManga by injectLazy()
    private val preferences: PreferencesHelper by injectLazy()

    fun load(manga: Manga, source: CatalogueSource): Flow<RelatedMangasResult> = buildPool(
        sourceTargets = listOf(manga to source),
        trackerManga = manga,
        includeTrackers = true,
        seedTrackerResults = emptyList(),
        excludedUrls = setOf(manga.url),
        excludedTitle = manga.title,
        cacheId = manga.id,
        cacheGet = relatedMangaCache::get,
        cachePut = relatedMangaCache::put,
    )

    /**
     * A single source's related mangas WITHOUT the tracker streams, for a per-source chip view of a
     * merged title. Tracker recommendations are title-level (identical across the grouped sources),
     * so they belong only in the unified view; running them per source would redundantly hit the
     * tracker APIs. Cached in its own namespace so it can't be served the with-tracker entry.
     */
    fun loadSourceOnly(
        manga: Manga,
        source: CatalogueSource,
        seedTrackerResults: List<Pair<String, List<SManga>>>,
    ): Flow<RelatedMangasResult> = buildPool(
        sourceTargets = listOf(manga to source),
        trackerManga = manga,
        includeTrackers = false,
        seedTrackerResults = seedTrackerResults,
        excludedUrls = setOf(manga.url),
        excludedTitle = manga.title,
        // Cached in its own per-source namespace so re-tapping a source chip is instant instead of
        // re-running the source's (rate-limited) related fetch each time. The replayed tracker seeds
        // are snapshotted at cache time; a later growth in the unified seeds isn't reflected until the
        // entry refreshes (bounded by the freshness window), an acceptable trade for the responsiveness.
        cacheId = manga.id,
        cacheGet = relatedMangaCache::getSource,
        cachePut = relatedMangaCache::putSource,
    )

    /**
     * Pooled related mangas for a merged group: the source-native + taste streams run for every
     * grouped source into one accumulator (so cross-source agreement is counted natively and the
     * ranker boosts titles several sources recommend), while the tracker stream runs once for
     * [anchorManga]. Cached in the pooled namespace keyed by the anchor id, so it never collides
     * with the anchor's own single-source entry. Dedup, ranking, and the 18/12 carousel slice are
     * identical to a single-source load.
     */
    fun loadPooled(
        sourceTargets: List<Pair<Manga, CatalogueSource>>,
        anchorManga: Manga,
    ): Flow<RelatedMangasResult> = buildPool(
        sourceTargets = sourceTargets,
        trackerManga = anchorManga,
        includeTrackers = true,
        seedTrackerResults = emptyList(),
        excludedUrls = sourceTargets.mapTo(HashSet()) { it.first.url },
        excludedTitle = anchorManga.title,
        cacheId = anchorManga.id,
        cacheGet = relatedMangaCache::getPooled,
        cachePut = relatedMangaCache::putPooled,
    )

    /**
     * Shared pool builder. [sourceTargets] each contribute a source-native + taste stream into one
     * accumulator; [trackerManga] contributes the single tracker stream. A single-element
     * [sourceTargets] reproduces the original single-source behavior exactly.
     */
    private fun buildPool(
        sourceTargets: List<Pair<Manga, CatalogueSource>>,
        trackerManga: Manga,
        includeTrackers: Boolean,
        seedTrackerResults: List<Pair<String, List<SManga>>>,
        excludedUrls: Set<String>,
        excludedTitle: String,
        cacheId: Long?,
        cacheGet: (Long) -> RelatedMangaCache.Entry?,
        cachePut: (Long, List<RelatedMangaCandidate>, List<RelatedMangaCandidate>) -> Unit,
    ): Flow<RelatedMangasResult> = channelFlow {
        var carousel: List<RelatedMangaCandidate> = emptyList()
        var fullPool: List<RelatedMangaCandidate> = emptyList()

        val cached = cacheId?.let(cacheGet)
        if (cached != null) {
            // Serve the cached pool immediately so reopening is instant.
            carousel = cached.carousel
            fullPool = cached.fullPool
            send(RelatedMangasResult(carousel, fullPool, loading = false))
            // Fresh enough -> done. Stale -> cards stay on screen while we refresh below.
            if (relatedMangaCache.isFresh(cached)) {
                relatedRecsLog { "cache HIT (fresh) manga=$cacheId size=${cached.carousel.size}, no fetch" }
                return@channelFlow
            }
            relatedRecsLog { "cache STALE manga=$cacheId, refreshing in background" }
        } else {
            relatedRecsLog { "cache MISS manga=$cacheId, fetching" }
        }

        // Show the loading indicator only when there's nothing cached to display yet.
        send(RelatedMangasResult(carousel, fullPool, loading = cached == null))

        // Inputs computed once up-front, captured by the per-push closure below. Read-only after
        // this point so no lock is needed when the ranker reads them.
        val rerankEnabled = preferences.enableRecommendationRerank().get()
        val taste: TasteProfile = runCatching {
            val entries = Injekt.get<GetTrackedEntries>().await()
            Injekt.get<ComputeTasteProfile>().invoke(entries)
        }.getOrElse {
            Logger.e(it) { "Taste profile load failed; ranker will run with empty profile" }
            TasteProfile.EMPTY
        }
        // Status-aware library hide. Every favorite is a suppression candidate, kept or hidden per
        // its tracker status (untracked favorites are always hidden). Match by (source, url) AND by
        // normalized title so tracker-origin recs (whose URL is a tracker URL) are caught too.
        val hideRC = preferences.hideTrackedReadingCompleted().get()
        val hideD = preferences.hideTrackedDropped().get()
        val hideOH = preferences.hideTrackedOnHold().get()
        val hidePTR = preferences.hideTrackedPlanToRead().get()
        val favorites = runCatching { getManga.awaitFavorites() }.getOrElse {
            Logger.e(it) { "Favorites lookup failed; library suppression will be a no-op" }
            emptyList()
        }
        val libraryStatuses: Map<Pair<Long, String>, Set<TrackStatus>> = if (favorites.isEmpty()) {
            emptyMap()
        } else {
            runCatching {
                Injekt.get<GetLibraryStatuses>().await()
            }.getOrElse {
                Logger.e(it) { "Library statuses lookup failed; status filter will be a no-op" }
                emptyMap()
            }
        }
        val hiddenFavorites = favorites.filter { fav ->
            val statuses = libraryStatuses[fav.source to fav.url].orEmpty()
            shouldHideLibraryEntry(statuses, hideRC, hideD, hideOH, hidePTR)
        }
        val libraryHidden: Set<Pair<Long, String>> = hiddenFavorites.mapTo(HashSet()) { it.source to it.url }
        val libraryHiddenTitles: Set<String> = hiddenFavorites.mapTo(HashSet()) { normalizeTitleForDedup(it.title) }
        relatedRecsLog {
            "library hide-set: ${libraryHidden.size} url + ${libraryHiddenTitles.size} title " +
                "(readingCompleted=$hideRC dropped=$hideD onHold=$hideOH planToRead=$hidePTR)"
        }
        val ranker = RecommendationRanker(
            wPersonal = preferences.recommendationStyle().get() / 100.0,
            wSerendipity = preferences.serendipity().get() / 100.0,
        )

        val accumulated = LinkedHashSet<RelatedMangaCandidate>()
        // Cross-pool dedup: tracker URLs and source URLs live in distinct namespaces, so url-keyed
        // dedup inside `accumulated` can't catch a manga appearing via both. Normalized title acts
        // as a second key spanning all streams. First-arriving entry wins, which prefers
        // source-origin candidates (single fast call) over tracker entries (slower, multiple calls).
        val seenTitleKeys = HashSet<String>().apply { add(normalizeTitleForDedup(excludedTitle)) }
        // Cross-source agreement: how many streams surfaced each normalized title. A title several
        // providers recommend is a stronger signal, so the ranker boosts it.
        val agreementByTitle = HashMap<String, Int>()
        val exceptionHandler: (Throwable) -> Unit = { e ->
            Logger.e(e) { "Related-mangas sub-task failed for ${trackerManga.title}" }
        }
        // Drop candidates already in the library. Source-origin matches by (source, url);
        // tracker-origin entries carry a tracker URL that never matches, so the title set catches
        // those (and any source result whose URL differs but title matches).
        val antiEcho: (RelatedMangaCandidate) -> Boolean = { c ->
            libraryHidden.contains(c.sourceId to c.manga.url) ||
                libraryHiddenTitles.contains(normalizeTitleForDedup(c.manga.title))
        }
        // Snapshot under the mutex, rank outside, then re-acquire to apply iff this push is still
        // the latest. Lets a slow ranker pass on push A run concurrently with push B's insert +
        // snapshot. Version check prevents A from clobbering B's newer ranked output if A's apply
        // phase happens to land after B's.
        val pushSeq = AtomicLong(0L)
        val appliedSeq = AtomicLong(0L)
        val mutex = Mutex()

        // Same accumulator for both input streams -- only the sourceId attached to each batch
        // differs. Mutex guards concurrent inserts since source-native + tracker fetchers race.
        fun makePushResults(
            sourceId: Long,
        ): suspend (Pair<String, List<SManga>>, Boolean) -> Unit = pushHandler@{ pair, _ ->
            // For tracker pushes (sourceId == RECOMMENDS_SOURCE) the bucket label is the tracker
            // name (e.g. "AniList") and lets the merge step round-robin slots fairly. For source
            // pushes it's the keyword/extension label and isn't needed downstream.
            val trackerName = pair.first.takeIf { sourceId == RECOMMENDS_SOURCE }
            val snapshot = mutex.withLock {
                val before = accumulated.size
                pair.second.forEach { m ->
                    if (m.url in excludedUrls) return@forEach
                    val titleKey = normalizeTitleForDedup(m.title)
                    agreementByTitle.merge(titleKey, 1, Int::plus)
                    if (!seenTitleKeys.add(titleKey)) return@forEach
                    accumulated.add(RelatedMangaCandidate(sourceId, trackerName, m))
                }
                if (accumulated.size == before) {
                    null
                } else {
                    val seq = pushSeq.incrementAndGet()
                    // Per-candidate agreement keyed by url so the ranker needs no title
                    // normalization. Separate snapshot of the unbounded pool feeds "See all".
                    val agreementByUrl = accumulated.associate {
                        it.manga.url to (agreementByTitle[normalizeTitleForDedup(it.manga.title)] ?: 1)
                    }
                    RelatedSnapshot(seq, mergeForDisplay(accumulated), accumulated.toList(), agreementByUrl)
                }
            } ?: return@pushHandler

            val (seq, merged, pool, agreementByUrl) = snapshot
            // Library suppression (anti-echo) runs in both modes; the ranker then only reorders.
            // The full rerank gates on the user toggle so the carousel can be returned to the
            // unranked ordering by flipping one preference.
            val visibleMerged = merged.filterNot(antiEcho)
            val visibleFull = pool.filterNot(antiEcho)
            if (merged.size != visibleMerged.size) {
                relatedRecsLog { "suppressed ${merged.size - visibleMerged.size} library title(s) from carousel" }
            }
            val boostedCount = agreementByUrl.values.count { it > 1 }
            if (boostedCount > 0) relatedRecsLog { "co-occurrence: $boostedCount title(s) surfaced by 2+ sources" }
            val newRelated = if (rerankEnabled) ranker.rank(visibleMerged, taste, agreementByUrl) else visibleMerged
            val newFullPool = if (rerankEnabled) ranker.rank(visibleFull, taste, agreementByUrl) else visibleFull

            val applied = mutex.withLock {
                if (appliedSeq.get() >= seq) {
                    false
                } else {
                    appliedSeq.set(seq)
                    carousel = newRelated
                    fullPool = newFullPool
                    true
                }
            }
            if (applied) send(RelatedMangasResult(carousel, fullPool, loading = true))
        }

        runCatching {
            coroutineScope {
                // Source-native + taste streams per grouped source (one source for a normal load);
                // they share the accumulator, so cross-source agreement is counted as they arrive.
                sourceTargets.forEach { (targetManga, targetSource) ->
                    launch {
                        targetSource.getRelatedMangaList(
                            manga = targetManga,
                            exceptionHandler = exceptionHandler,
                            pushResults = makePushResults(targetSource.id),
                        )
                    }
                    launch {
                        TasteCandidateFetcher().fetch(
                            source = targetSource,
                            exceptionHandler = exceptionHandler,
                            pushResults = makePushResults(targetSource.id),
                        )
                    }
                }
                // Trackers are title-level, so the tracker stream runs once for the anchor. A
                // per-source view doesn't re-fetch; instead it replays the recs already fetched in
                // the unified view (no API call) so they still show alongside the source's natives.
                if (includeTrackers) {
                    launch {
                        RecommendationsFetcher().fetch(
                            manga = trackerManga,
                            exceptionHandler = exceptionHandler,
                            pushResults = makePushResults(RECOMMENDS_SOURCE),
                        )
                    }
                } else if (seedTrackerResults.isNotEmpty()) {
                    launch {
                        val push = makePushResults(RECOMMENDS_SOURCE)
                        seedTrackerResults.forEach { (trackerName, mangas) -> push(trackerName to mangas, false) }
                    }
                }
            }
        }.onFailure {
            Logger.e(it) { "Related-mangas fetch failed for ${trackerManga.title}" }
        }

        // Cache the resolved pool so a reopen within the freshness window is instant. Never cache an
        // empty pool: the source's related endpoint is flaky and an empty result is usually
        // transient, so caching it would collapse the carousel for the whole freshness window and
        // hide recommendations that a retry would return.
        if (fullPool.isNotEmpty()) {
            cacheId?.let { cachePut(it, carousel, fullPool) }
            relatedRecsLog { "fetched+cached manga=$cacheId size=${carousel.size}" }
        } else {
            relatedRecsLog { "fetched manga=$cacheId size=0, not caching (likely transient empty)" }
        }
        send(RelatedMangasResult(carousel, fullPool, loading = false))
    }.flowOn(Dispatchers.IO)

    /** Apply the user's status-based hide policy to a single library entry. Empty status set =
     *  "in library, untracked" -> always hide. PLAN_TO_READ / ON_HOLD are "reminder" statuses. */
    private fun shouldHideLibraryEntry(
        statuses: Set<TrackStatus>,
        hideReadingCompleted: Boolean,
        hideDropped: Boolean,
        hideOnHold: Boolean,
        hidePlanToRead: Boolean,
    ): Boolean {
        if (statuses.isEmpty()) return true
        return statuses.any { s ->
            when (s) {
                TrackStatus.READING, TrackStatus.COMPLETED, TrackStatus.UNKNOWN -> hideReadingCompleted
                TrackStatus.DROPPED -> hideDropped
                TrackStatus.ON_HOLD -> hideOnHold
                TrackStatus.PLAN_TO_READ -> hidePlanToRead
            }
        }
    }

    /** Slice the accumulated pool into the carousel-visible list, reserving up to
     *  [RELATED_MANGAS_TRACKER_RESERVE] slots for tracker-origin entries (round-robin across
     *  trackers) so they aren't starved when source-native fills the cap first. */
    private fun mergeForDisplay(pool: LinkedHashSet<RelatedMangaCandidate>): List<RelatedMangaCandidate> {
        val sourceList = pool.filterNot { it.sourceId == RECOMMENDS_SOURCE }
        val trackerLists = pool
            .filter { it.sourceId == RECOMMENDS_SOURCE }
            .groupBy { it.trackerName }
            .values
            .toList()
        val trackerTotal = trackerLists.sumOf { it.size }
        val initialTracker = minOf(trackerTotal, RELATED_MANGAS_TRACKER_RESERVE)
        val sourceTake = minOf(sourceList.size, RELATED_MANGAS_LIMIT - initialTracker)
        val trackerCap = minOf(trackerTotal, RELATED_MANGAS_LIMIT - sourceTake)
        return sourceList.take(sourceTake) + roundRobin(trackerLists, trackerCap)
    }

    /** Interleave [lists] in round-robin order, stopping at [limit]. Empty lists are skipped;
     *  longer lists drain into remaining iterations once shorter ones are exhausted. */
    private fun <T> roundRobin(lists: List<List<T>>, limit: Int): List<T> {
        if (limit <= 0 || lists.isEmpty()) return emptyList()
        val iterators = lists.map { it.iterator() }.filter { it.hasNext() }.toMutableList()
        val out = ArrayList<T>(limit)
        while (out.size < limit && iterators.isNotEmpty()) {
            val it = iterators.iterator()
            while (it.hasNext() && out.size < limit) {
                val cur = it.next()
                out.add(cur.next())
                if (!cur.hasNext()) it.remove()
            }
        }
        return out
    }

    /** Debug-only logcat aid for the related-mangas pool, filterable via tag:RelatedRecs. */
    private fun relatedRecsLog(message: () -> String) {
        if (BuildConfig.DEBUG) Logger.withTag("RelatedRecs").d(message())
    }

    /** One push's view of the pool: sequence, carousel slice, full pool, and per-candidate
     *  cross-source agreement count (keyed by url) the ranker boosts on. */
    private data class RelatedSnapshot(
        val seq: Long,
        val merged: List<RelatedMangaCandidate>,
        val fullPool: List<RelatedMangaCandidate>,
        val agreementByUrl: Map<String, Int>,
    )

    companion object {
        /** Carousel display cap; the full pool feeds "See all". */
        const val RELATED_MANGAS_LIMIT = 30

        /** Minimum carousel slots reserved for tracker-origin recommendations so they aren't
         *  starved when source-native + keyword-search fill the cap first. Either side cedes
         *  unfilled capacity to the other. */
        private const val RELATED_MANGAS_TRACKER_RESERVE = 12

        /** Collapse every run of non-alphanumeric characters (punctuation, symbols, whitespace,
         *  straight vs curly apostrophes) into a single separator, so the same title from different
         *  sources dedups even when only its punctuation differs. Letters (incl. CJK) and digits
         *  are kept, so titles differing by a number ("Season 1" vs "Season 2") stay distinct. */
        private val DEDUP_SEPARATORS = Regex("[^\\p{L}\\p{N}]+")

        fun normalizeTitleForDedup(title: String): String =
            title.lowercase().replace(DEDUP_SEPARATORS, " ").trim()
    }
}
