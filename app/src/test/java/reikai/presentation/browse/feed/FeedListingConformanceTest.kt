package reikai.presentation.browse.feed

import eu.kanade.tachiyomi.source.model.MangasPage
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.domain.novel.FavoritedNovels
import reikai.domain.source.CatalogueEnd
import reikai.domain.source.SourceKey
import reikai.novel.source.NovelItemsPage
import reikai.novel.source.NovelListing
import reikai.novel.source.NovelSource
import reikai.presentation.browse.catalogue.EntryBrowseListing
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import tachiyomi.domain.manga.model.Manga
import eu.kanade.tachiyomi.source.Source as LiveSource

/** A row with no saved search fills from its source's Latest, or Popular where it has none, for both types. */
class FeedListingConformanceTest {

    private var fetched: EntryBrowseListing? = null

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a row on a source without Latest fills from Popular`(type: ContentType) = runTest {
        val (provider, row) = feed(type, supportsLatest = false)

        provider.load(row, savedSearch = null)

        fetched shouldBe EntryBrowseListing.Popular
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a row on a source with Latest fills from Latest`(type: ContentType) = runTest {
        val (provider, row) = feed(type, supportsLatest = true)

        provider.load(row, savedSearch = null)

        fetched shouldBe EntryBrowseListing.Latest
    }

    private fun feed(type: ContentType, supportsLatest: Boolean): Pair<FeedProvider, BrowseSearchRow> = when (type) {
        ContentType.MANGA -> mangaFeed(supportsLatest)
        ContentType.NOVELS -> novelFeed(supportsLatest)
        else -> error("no feed for $type")
    }

    private fun mangaFeed(supportsLatest: Boolean): Pair<FeedProvider, BrowseSearchRow> {
        val source = mockk<LiveSource> {
            every { id } returns 1L
            every { this@mockk.supportsLatest } returns supportsLatest
            coEvery { getLatestUpdates(1) } answers { fetched(EntryBrowseListing.Latest) }
            coEvery { getPopularManga(1) } answers { fetched(EntryBrowseListing.Popular) }
        }
        val provider = MangaFeedProvider(
            sourceManager = mockk(),
            getEnabledSources = mockk(),
            networkToLocalManga = mockk { coEvery { this@mockk.invoke(any<List<Manga>>()) } returns emptyList() },
            getManga = mockk(),
        )
        return provider to row(SourceKey.Manga(1L), source)
    }

    private fun novelFeed(supportsLatest: Boolean): Pair<FeedProvider, BrowseSearchRow> {
        val source = mockk<NovelSource>(relaxed = true) {
            every { id } returns "plugin"
            every { this@mockk.supportsLatest } returns supportsLatest
            every { filters } returns null
            coEvery { browse(any(), any(), any()) } answers {
                fetched = when (firstArg<NovelListing>()) {
                    NovelListing.Latest -> EntryBrowseListing.Latest
                    NovelListing.Popular -> EntryBrowseListing.Popular
                }
                NovelItemsPage(emptyList(), CatalogueEnd.Reported(false))
            }
        }
        val provider = NovelFeedProvider(mockk(), mockk(), MutableStateFlow(FavoritedNovels.None))
        return provider to row(SourceKey.Novel("plugin"), source)
    }

    private fun fetched(listing: EntryBrowseListing): MangasPage {
        fetched = listing
        return MangasPage(emptyList(), false)
    }

    private fun row(key: SourceKey, source: Any) =
        BrowseSearchRow(key, "Source", "en", isPinned = false, state = EntrySearchState.Loading, source = source)
}
