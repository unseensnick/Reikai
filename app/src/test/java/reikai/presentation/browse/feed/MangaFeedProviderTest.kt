package reikai.presentation.browse.feed

import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.tachiyomi.source.CatalogueSource
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.source.SourceKey
import tachiyomi.domain.source.model.Source
import tachiyomi.source.local.LocalSource

/**
 * The feed's add picker offers the manga sources the Sources tab lists: enabled languages plus the
 * local source, which has no language to enable, without the disabled ones and without the
 * last-used duplicate the Sources tab draws as its own section.
 */
class MangaFeedProviderTest {

    private fun source(id: Long, lang: String = "en") =
        Source(id = id, lang = lang, name = "source $id", supportsLatest = false, isStub = false)

    private fun catalogueSource(sourceId: Long) = mockk<CatalogueSource> {
        every { id } returns sourceId
        every { name } returns "source $sourceId"
        every { lang } returns "en"
    }

    private fun provider(
        sources: List<Source>,
        disabled: Set<String> = emptySet(),
        lastUsed: SourceKey? = null,
    ): MangaFeedProvider {
        val getEnabledSources = GetEnabledSources(
            repository = mockk { every { getSources() } returns flowOf(sources) },
            preferences = mockk {
                every { pinnedSources } returns mockk { every { changes() } returns flowOf(emptySet()) }
                every { enabledLanguages } returns mockk { every { changes() } returns flowOf(setOf("en")) }
                every { disabledSources } returns mockk { every { changes() } returns flowOf(disabled) }
            },
            reikaiPreferences = mockk {
                every { lastUsedSource } returns mockk { every { changes() } returns flowOf(lastUsed) }
            },
        )
        return MangaFeedProvider(
            sourceManager = mockk {
                coEvery { this@mockk.get(any<Long>()) } answers { catalogueSource(firstArg()) }
            },
            getEnabledSources = getEnabledSources,
            networkToLocalManga = mockk(),
            getManga = mockk(),
        )
    }

    @Test
    fun `the last-used source is offered once`() = runTest {
        provider(listOf(source(1L)), lastUsed = SourceKey.Manga(1L)).sources().map { it.key } shouldBe
            listOf(SourceKey.Manga(1L))
    }

    @Test
    fun `a disabled source is not offered`() = runTest {
        provider(listOf(source(1L), source(2L)), disabled = setOf("2")).sources().map { it.key } shouldBe
            listOf(SourceKey.Manga(1L))
    }

    @Test
    fun `the local source is offered with no language enabled for it`() = runTest {
        provider(listOf(source(LocalSource.ID, lang = "other"))).sources().map { it.key } shouldBe
            listOf(SourceKey.Manga(LocalSource.ID))
    }
}
