package eu.kanade.domain

import eu.kanade.tachiyomi.source.online.MetadataSource
import io.kotest.matchers.shouldNotBe
import io.mockk.mockk
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import uy.kohesive.injekt.api.InjektScope
import uy.kohesive.injekt.api.addSingleton
import uy.kohesive.injekt.registry.default.DefaultRegistrar

/**
 * [DomainModule] is the last Injekt module, kept only for `source-api`'s three [MetadataSource]
 * contracts, which installed extensions compile against.
 *
 * Its registrations resolve their dependencies through untyped `get()`, so a missing one fails at
 * runtime rather than at compile time, and a text search cannot find which: the type never appears
 * at the call site. This resolves instead of matching, so every registration is built for real.
 */
class DomainModuleTest {

    /**
     * A scope of its own, never the global `Injekt`: a test that registered into the global one would
     * leak its stubs into every later test in the same JVM.
     */
    private val scope = InjektScope(DefaultRegistrar()).apply {
        // Everything the registrations resolve that lives in the graph rather than in this module.
        // Stubs, because the point is that resolution completes, not what the dependencies do.
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

        /** The types something outside the graph still resolves by hand. */
        @JvmStatic
        fun typesWithALiveConsumer(): List<Class<*>> = listOf(
            // source-api's MetadataSource, by bare Injekt.get(), so extensions can reach them.
            MetadataSource.GetMangaId::class.java,
            MetadataSource.GetFlatMetadataById::class.java,
            MetadataSource.InsertFlatMetadata::class.java,
        )
    }
}
