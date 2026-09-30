package reikai.presentation.browse.feed

import eu.kanade.domain.source.interactor.GetEnabledSources
import eu.kanade.tachiyomi.ui.browse.source.SourcesViewModel
import exh.source.EHENTAI_EXT_SOURCES
import exh.source.EH_SOURCE_ID
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.source.SourceKey
import reikai.presentation.browse.source.MangaSourcesProvider
import tachiyomi.domain.source.model.Source
import tachiyomi.source.local.LocalSource
import eu.kanade.tachiyomi.source.Source as LiveSource

/**
 * The feed's add picker offers the manga sources the Sources tab lists: enabled languages plus the
 * local source, which has no language to enable, without the disabled ones and without the
 * last-used duplicate the Sources tab draws as its own section.
 */
class MangaFeedProviderTest {

    private fun source(id: Long, lang: String = "en") =
        Source(id = id, lang = lang, name = "source $id", supportsLatest = false, isStub = false)

    // The base contract and nothing narrower, which is all the local source implements.
    private fun liveSource(sourceId: Long) = mockk<LiveSource> {
        every { id } returns sourceId
        every { name } returns "source $sourceId"
        every { lang } returns "en"
    }

    private fun enabledSources(
        sources: List<Source>,
        languages: Set<String> = setOf("en"),
        disabled: Set<String> = emptySet(),
        lastUsed: SourceKey? = null,
    ) = GetEnabledSources(
        repository = mockk { every { getSources() } returns flowOf(sources) },
        preferences = mockk {
            every { pinnedSources } returns mockk { every { changes() } returns flowOf(emptySet()) }
            every { enabledLanguages } returns mockk { every { changes() } returns flowOf(languages) }
            every { disabledSources } returns mockk { every { changes() } returns flowOf(disabled) }
        },
        reikaiPreferences = mockk {
            every { lastUsedSource } returns mockk { every { changes() } returns flowOf(lastUsed) }
        },
    )

    private fun provider(getEnabledSources: GetEnabledSources) = MangaFeedProvider(
        sourceManager = mockk {
            coEvery { this@mockk.get(any<Long>()) } answers { liveSource(firstArg()) }
        },
        getEnabledSources = getEnabledSources,
        networkToLocalManga = mockk(),
        getManga = mockk(),
    )

    @Test
    fun `the last-used source is offered once`() = runTest {
        val enabled = enabledSources(listOf(source(1L)), lastUsed = SourceKey.Manga(1L))

        provider(enabled).sources().map { it.key } shouldBe listOf(SourceKey.Manga(1L))
    }

    @Test
    fun `a disabled source is not offered`() = runTest {
        val enabled = enabledSources(listOf(source(1L), source(2L)), disabled = setOf("2"))

        provider(enabled).sources().map { it.key } shouldBe listOf(SourceKey.Manga(1L))
    }

    @Test
    fun `the local source is offered with no language enabled for it`() = runTest {
        val enabled = enabledSources(listOf(source(LocalSource.ID, lang = "other")))

        provider(enabled).sources().map { it.key } shouldBe listOf(SourceKey.Manga(LocalSource.ID))
    }

    @Test
    fun `a feed row added on the local source resolves back to it`() = runTest {
        provider(enabledSources(emptyList())).source(SourceKey.Manga(LocalSource.ID))?.key shouldBe
            SourceKey.Manga(LocalSource.ID)
    }

    /** One built-in site registered once per language is one source per enabled language in both lists. */
    @Test
    fun `the picker offers exactly the sources the Sources tab lists`() = runTest {
        val english = EHENTAI_EXT_SOURCES.entries.first { it.value == "en" }.key
        val japanese = EHENTAI_EXT_SOURCES.entries.first { it.value == "ja" }.key
        val enabled = enabledSources(
            sources = listOf(
                source(LocalSource.ID, lang = "other"),
                source(EH_SOURCE_ID, lang = "all"),
                source(english, lang = "en"),
                source(japanese, lang = "ja"),
                source(1L),
                source(2L),
            ),
            languages = setOf("en", "all"),
            disabled = setOf("2"),
            lastUsed = SourceKey.Manga(1L),
        )
        val sourcesTab = MangaSourcesProvider(SourcesViewModel(enabled, mockk(), mockk()), flowOf(emptyList()))
            .rows.filterNotNull().first()

        provider(enabled).sources().map { it.key } shouldBe sourcesTab.filterNot { it.isUsedLast }.map { it.key }
    }
}
