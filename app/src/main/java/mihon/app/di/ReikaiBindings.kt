package mihon.app.di

import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.BindingContainer
import dev.zacsweers.metro.Provider
import dev.zacsweers.metro.Provides
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.data.track.TrackerManager
import reikai.domain.library.ReikaiLibraryPreferences
import reikai.domain.manga.MangaMergeManager
import reikai.domain.manga.PropagateTrackerLinks
import reikai.domain.merge.MergeGroupRepository
import reikai.domain.novel.NovelMergeManager
import reikai.domain.novel.track.PropagateNovelTrackerLinks
import reikai.domain.recommendation.ReikaiRecommendationPreferences
import reikai.domain.recommendation.taste.AnilistLibraryFetcher
import reikai.domain.recommendation.taste.BangumiLibraryFetcher
import reikai.domain.recommendation.taste.KitsuLibraryFetcher
import reikai.domain.recommendation.taste.MyAnimeListLibraryFetcher
import reikai.domain.recommendation.taste.ShikimoriLibraryFetcher
import reikai.domain.recommendation.taste.TrackerLibraryFetcher

/**
 * The Reikai-owned bindings that cannot be a plain annotation on the class.
 */
@BindingContainer
object ReikaiBindings {

    // The propagator arrives as a Provider because it depends on the manager it is being handed to.
    // Three cycles run through this one edge: each manager to its own propagator, and the novel
    // propagator again through GetNovelTracks. The lambda only ever runs inside a suspend function,
    // never during construction, so deferring it is safe.
    @Provides
    @SingleIn(AppScope::class)
    fun providesMangaMergeManager(
        repository: MergeGroupRepository,
        preferences: ReikaiLibraryPreferences,
        propagate: Provider<PropagateTrackerLinks>,
    ): MangaMergeManager = MangaMergeManager(repository, preferences) { propagate().distribute(it) }

    @Provides
    @SingleIn(AppScope::class)
    fun providesNovelMergeManager(
        repository: MergeGroupRepository,
        preferences: ReikaiLibraryPreferences,
        propagate: Provider<PropagateNovelTrackerLinks>,
    ): NovelMergeManager = NovelMergeManager(repository, preferences) { propagate().distribute(it) }

    // Each fetcher wants a concrete tracker, and those are properties of the TrackerManager
    // singleton rather than bindings of their own. Binding them separately would build second
    // tracker instances carrying their own login state, so the list is assembled from the singleton.
    @Provides
    fun providesTrackerLibraryFetchers(
        trackerManager: TrackerManager,
        preferences: ReikaiRecommendationPreferences,
    ): List<TrackerLibraryFetcher> = listOf(
        AnilistLibraryFetcher(trackerManager.aniList, preferences),
        MyAnimeListLibraryFetcher(trackerManager.myAnimeList, preferences),
        KitsuLibraryFetcher(trackerManager.kitsu, preferences),
        ShikimoriLibraryFetcher(trackerManager.shikimori, preferences),
        BangumiLibraryFetcher(trackerManager.bangumi, preferences),
    )
}
