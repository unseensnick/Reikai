package eu.kanade.domain

import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.tachiyomi.source.online.MetadataSource
import reikai.domain.category.GetNovelCategories
import reikai.domain.novel.interactor.DeleteNovelChaptersAfterRead
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.InsertNovelTrack
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.SetNovelViewerFlags
import reikai.domain.novel.interactor.UpsertNovelHistory
import reikai.domain.novel.track.TrackNovelChapter
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.get

/**
 * The last Injekt module, and only for what cannot reach the Metro graph. Everything else the app
 * builds now comes from `AppGraph`; what is left here has a consumer that resolves by hand:
 *
 * - the novel reader ([reikai.presentation.novel.reader.NovelReaderScreenModel]), which stays on
 *   Injekt by design until the tsundoku reader migration deletes it;
 * - `source-api`'s three [MetadataSource] contracts, which installed extensions compile against, so
 *   they are permanent rather than pending.
 *
 * Three more are here only because one of those takes them, which is not visible at any call site.
 * `DomainModuleTest` resolves all twelve for real, so removing one that is still needed fails there
 * instead of at runtime. Add nothing new: a net-new type belongs in the graph.
 */
class DomainModule : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        // Read by NovelReaderScreenModel through injectLazy.
        addFactory { GetNovelCategories(get()) }
        addFactory { SetNovelViewerFlags(get()) }
        addFactory { UpsertNovelHistory(get()) }
        addFactory { SetNovelReadStatus(get(), get()) }
        addFactory { TrackNovelChapter(get(), get(), get(), get()) }
        addFactory { GetIncognitoState(get(), get(), get()) }

        // Reached only through the six above. The download manager stays a lambda: building it
        // restores the persisted queue and can start the worker, which marking a chapter read
        // must not do.
        addFactory { DeleteNovelChaptersAfterRead(get(), get(), { Injekt.get() }, get()) }
        addFactory { GetNovelTracks(get(), get(), get()) }
        addFactory { InsertNovelTrack(get()) }

        // RK: the adult/EXH gallery-metadata contracts, resolved inside source-api so an installed
        // extension can reach them. The unqualified interactors are graph-owned; only these three
        // qualified bindings are Injekt's.
        addFactory<MetadataSource.GetMangaId> { GetManga(get()) }
        addFactory<MetadataSource.GetFlatMetadataById> { GetFlatMetadataById(get()) }
        addFactory<MetadataSource.InsertFlatMetadata> { InsertFlatMetadata(get()) }
    }
}
