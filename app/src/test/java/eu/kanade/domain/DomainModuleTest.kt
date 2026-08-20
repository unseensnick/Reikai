package eu.kanade.domain

import eu.kanade.domain.source.interactor.GetIncognitoState
import eu.kanade.tachiyomi.source.online.MetadataSource
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.category.GetNovelCategories
import reikai.domain.novel.interactor.DeleteNovelChaptersAfterRead
import reikai.domain.novel.interactor.GetNovelTracks
import reikai.domain.novel.interactor.InsertNovelTrack
import reikai.domain.novel.interactor.SetNovelReadStatus
import reikai.domain.novel.interactor.SetNovelViewerFlags
import reikai.domain.novel.interactor.UpsertNovelHistory
import reikai.domain.novel.track.TrackNovelChapter
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

/**
 * [DomainModule] is the last Injekt module, kept only for the consumers that cannot reach the graph:
 * the novel reader, which stays on Injekt until the tsundoku migration, and `source-api`'s three
 * [MetadataSource] contracts, which installed extensions compile against.
 *
 * Its registrations resolve each other through `get()`, so deleting one another still needs fails at
 * runtime rather than at compile time, and 64 untyped `Injekt.get()` sites make a text search useless
 * for finding out which. This resolves instead of matching: every type with a live consumer is built
 * for real, so a deletion that breaks a dependency fails here.
 */
class DomainModuleTest {

    /**
     * A scope of its own, never the global `Injekt`: a test that registered into the global one would
     * leak its stubs into every later test in the same JVM.
     */
    private val scope = InjektScope(DefaultRegistrar()).apply {
        // Everything the survivors resolve that lives in the graph rather than in this module. Stubs,
        // because the point is that resolution completes, not what the dependencies do.
        addSingleton<tachiyomi.domain.category.repository.CategoryRepository>(mockk(relaxed = true))
        addSingleton<reikai.domain.novel.NovelChapterRepository>(mockk(relaxed = true))
        addSingleton<reikai.domain.novel.NovelRepository>(mockk(relaxed = true))
        addSingleton<reikai.domain.novel.NovelHistoryRepository>(mockk(relaxed = true))
        addSingleton<reikai.domain.novel.NovelTrackRepository>(mockk(relaxed = true))
        addSingleton<reikai.domain.novel.NovelPreferences>(mockk(relaxed = true))
        addSingleton<reikai.domain.novel.NovelMergeManager>(mockk(relaxed = true))
        addSingleton<reikai.domain.novel.track.NovelDelayedTrackingStore>(mockk(relaxed = true))
        addSingleton<reikai.domain.library.ReikaiLibraryPreferences>(mockk(relaxed = true))
        addSingleton<eu.kanade.tachiyomi.data.track.TrackerManager>(mockk(relaxed = true))
        addSingleton<eu.kanade.domain.base.BasePreferences>(mockk(relaxed = true))
        addSingleton<eu.kanade.domain.source.service.SourcePreferences>(mockk(relaxed = true))
        addSingleton<eu.kanade.tachiyomi.extension.ExtensionManager>(mockk(relaxed = true))
        addSingleton<tachiyomi.domain.manga.repository.MangaRepository>(mockk(relaxed = true))
        addSingleton<tachiyomi.domain.manga.repository.MangaMetadataRepository>(mockk(relaxed = true))

        importModule(DomainModule())
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("typesWithALiveConsumer")
    fun `a type with a live Injekt consumer still resolves`(type: Class<*>) {
        scope.getInstance<Any>(type) shouldNotBe null
    }

    companion object {

        /**
         * The nine types something outside the graph still resolves by hand, plus the three that only
         * survive because one of those nine takes them. Add a row here when a new Injekt consumer
         * appears; remove one only when its last consumer is converted.
         */
        @JvmStatic
        fun typesWithALiveConsumer(): List<Class<*>> = listOf(
            // NovelReaderScreenModel, by injectLazy.
            UpsertNovelHistory::class.java,
            SetNovelViewerFlags::class.java,
            GetNovelCategories::class.java,
            SetNovelReadStatus::class.java,
            TrackNovelChapter::class.java,
            GetIncognitoState::class.java,
            // source-api's MetadataSource, by bare Injekt.get(), so extensions can reach them.
            MetadataSource.GetMangaId::class.java,
            MetadataSource.GetFlatMetadataById::class.java,
            MetadataSource.InsertFlatMetadata::class.java,
            // Reached only through the six above, and just as fatal to delete.
            DeleteNovelChaptersAfterRead::class.java,
            GetNovelTracks::class.java,
            InsertNovelTrack::class.java,
        )
    }
}
