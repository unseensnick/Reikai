package yokai.core.di

import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.data.track.anilist.AnilistLibraryFetcher
import eu.kanade.tachiyomi.data.track.kitsu.KitsuLibraryFetcher
import eu.kanade.tachiyomi.data.track.myanimelist.MyAnimeListLibraryFetcher
import org.koin.dsl.module
import yokai.data.category.CategoryRepositoryImpl
import yokai.data.chapter.ChapterRepositoryImpl
import yokai.data.extension.repo.ExtensionRepoRepositoryImpl
import yokai.data.history.HistoryRepositoryImpl
import yokai.data.library.custom.CustomMangaRepositoryImpl
import yokai.data.library.taste.TrackerLibraryRepositoryImpl
import yokai.data.manga.MangaRepositoryImpl
import yokai.data.novel.NovelChapterRepositoryImpl
import yokai.data.novel.NovelRepositoryImpl
import yokai.data.novel.NovelTrackRepositoryImpl
import yokai.data.source.browse.filter.SavedSearchRepositoryImpl
import yokai.data.track.TrackRepositoryImpl
import yokai.domain.category.CategoryRepository
import yokai.domain.category.interactor.DeleteCategories
import yokai.domain.category.interactor.GetCategories
import yokai.domain.category.interactor.InsertCategories
import yokai.domain.category.interactor.SetMangaCategories
import yokai.domain.category.interactor.UpdateCategories
import yokai.domain.chapter.ChapterRepository
import yokai.domain.chapter.interactor.DeleteChapter
import yokai.domain.chapter.interactor.GetAvailableScanlators
import yokai.domain.chapter.interactor.GetChapter
import yokai.domain.chapter.interactor.InsertChapter
import yokai.domain.chapter.interactor.UpdateChapter
import yokai.domain.extension.interactor.TrustExtension
import yokai.domain.extension.repo.ExtensionRepoRepository
import yokai.domain.extension.repo.interactor.CreateExtensionRepo
import yokai.domain.extension.repo.interactor.DeleteExtensionRepo
import yokai.domain.extension.repo.interactor.GetExtensionRepo
import yokai.domain.extension.repo.interactor.GetExtensionRepoCount
import yokai.domain.extension.repo.interactor.ReplaceExtensionRepo
import yokai.domain.extension.repo.interactor.UpdateExtensionRepo
import yokai.domain.history.HistoryRepository
import yokai.domain.history.interactor.GetHistory
import yokai.domain.history.interactor.UpsertHistory
import yokai.domain.library.custom.CustomMangaRepository
import yokai.domain.library.custom.interactor.CreateCustomManga
import yokai.domain.library.custom.interactor.DeleteCustomManga
import yokai.domain.library.custom.interactor.GetCustomManga
import yokai.domain.library.custom.interactor.RelinkCustomManga
import yokai.domain.library.taste.TrackerLibraryRepository
import yokai.domain.library.taste.interactor.ComputeTasteProfile
import yokai.domain.library.taste.interactor.GetLibraryStatuses
import yokai.domain.library.taste.interactor.GetTrackedEntries
import yokai.domain.library.taste.interactor.RefreshTrackerLibrary
import yokai.domain.manga.MangaRepository
import yokai.domain.novel.NovelChapterRepository
import yokai.domain.novel.NovelRepository
import yokai.domain.novel.NovelTrackRepository
import yokai.domain.manga.interactor.GetLibraryManga
import yokai.domain.manga.interactor.GetManga
import yokai.domain.manga.interactor.InsertManga
import yokai.domain.manga.interactor.UpdateManga
import yokai.domain.recents.interactor.GetRecents
import yokai.domain.source.browse.filter.FilterSerializer
import yokai.domain.source.browse.filter.SavedSearchRepository
import yokai.domain.source.browse.filter.interactor.DeleteSavedSearch
import yokai.domain.source.browse.filter.interactor.GetSavedSearch
import yokai.domain.source.browse.filter.interactor.InsertSavedSearch
import yokai.domain.track.TrackRepository
import yokai.domain.track.interactor.DeleteTrack
import yokai.domain.track.interactor.GetTrack
import yokai.domain.track.interactor.InsertTrack

fun domainModule() = module {
    factory { TrustExtension(get(), get()) }

    single<CategoryRepository> { CategoryRepositoryImpl(get()) }
    factory { DeleteCategories(get()) }
    factory { GetCategories(get()) }
    factory { InsertCategories(get()) }
    factory { UpdateCategories(get()) }

    single<ExtensionRepoRepository> { ExtensionRepoRepositoryImpl(get()) }
    factory { CreateExtensionRepo(get()) }
    factory { DeleteExtensionRepo(get()) }
    factory { GetExtensionRepo(get()) }
    factory { GetExtensionRepoCount(get()) }
    factory { ReplaceExtensionRepo(get()) }
    factory { UpdateExtensionRepo(get(), get()) }

    single<CustomMangaRepository> { CustomMangaRepositoryImpl(get()) }
    factory { CreateCustomManga(get()) }
    factory { DeleteCustomManga(get()) }
    factory { GetCustomManga(get()) }
    factory { RelinkCustomManga(get()) }

    single<MangaRepository> { MangaRepositoryImpl(get()) }
    factory { GetManga(get()) }
    factory { GetLibraryManga(get()) }
    factory { InsertManga(get()) }
    factory { UpdateManga(get()) }

    single<NovelRepository> { NovelRepositoryImpl(get()) }
    single<NovelChapterRepository> { NovelChapterRepositoryImpl(get()) }
    single<NovelTrackRepository> { NovelTrackRepositoryImpl(get()) }

    factory { SetMangaCategories(get()) }

    single<ChapterRepository> { ChapterRepositoryImpl(get()) }
    factory { DeleteChapter(get()) }
    factory { GetAvailableScanlators(get()) }
    factory { GetChapter(get()) }
    factory { InsertChapter(get()) }
    factory { UpdateChapter(get()) }

    single<HistoryRepository> { HistoryRepositoryImpl(get()) }
    factory { GetHistory(get()) }
    factory { UpsertHistory(get()) }

    factory { GetRecents(get(), get()) }

    single<TrackRepository> { TrackRepositoryImpl(get()) }
    factory { DeleteTrack(get()) }
    factory { GetTrack(get()) }
    factory { InsertTrack(get()) }

    single<TrackerLibraryRepository> { TrackerLibraryRepositoryImpl(get(), get()) }
    factory { GetTrackedEntries(get()) }
    factory { GetLibraryStatuses(get(), get(), get(), get()) }
    factory { ComputeTasteProfile() }
    // One entry per supported tracker — the full Phase 4 set (AniList + MAL + Kitsu) is live.
    factory {
        val trackManager = get<TrackManager>()
        RefreshTrackerLibrary(
            get(),
            listOf(
                AnilistLibraryFetcher(trackManager.aniList),
                MyAnimeListLibraryFetcher(trackManager.myAnimeList),
                KitsuLibraryFetcher(trackManager.kitsu),
            ),
            get(),
            get(),
        )
    }

    single<SavedSearchRepository> { SavedSearchRepositoryImpl(get()) }
    factory { DeleteSavedSearch(get()) }
    factory { GetSavedSearch(get()) }
    factory { InsertSavedSearch(get()) }
    factory { FilterSerializer() }
}
