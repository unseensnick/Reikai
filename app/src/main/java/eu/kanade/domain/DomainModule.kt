package eu.kanade.domain

import eu.kanade.tachiyomi.source.online.MetadataSource
import tachiyomi.domain.manga.interactor.GetFlatMetadataById
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.interactor.InsertFlatMetadata
import uy.kohesive.injekt.api.InjektModule
import uy.kohesive.injekt.api.InjektRegistrar
import uy.kohesive.injekt.api.addFactory
import uy.kohesive.injekt.api.get

/**
 * The last Injekt module, and only for what cannot reach the Metro graph: `source-api`'s three
 * [MetadataSource] contracts, which installed extensions compile against, so they are permanent.
 * `DomainModuleTest` resolves all three for real, so removing one that is still needed fails there
 * instead of at runtime. Add nothing new: a net-new type belongs in the graph.
 */
class DomainModule : InjektModule {

    override fun InjektRegistrar.registerInjectables() {
        // RK: the adult/EXH gallery-metadata contracts, resolved inside source-api so an installed
        // extension can reach them. The unqualified interactors are graph-owned; only these three
        // qualified bindings are Injekt's.
        addFactory<MetadataSource.GetMangaId> { GetManga(get()) }
        addFactory<MetadataSource.GetFlatMetadataById> { GetFlatMetadataById(get()) }
        addFactory<MetadataSource.InsertFlatMetadata> { InsertFlatMetadata(get()) }
    }
}
