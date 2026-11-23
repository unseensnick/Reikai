package reikai.presentation.browse

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.source.SourceKey
import reikai.novel.source.NovelExtensionFormat
import reikai.novel.source.NovelExtensionFormat.APK
import reikai.novel.source.NovelExtensionFormat.IREADER
import reikai.novel.source.NovelSource
import reikai.presentation.browse.catalogue.EntryBrowseListing
import reikai.presentation.browse.components.sourceDetail
import reikai.presentation.browse.feed.FeedEntry
import reikai.presentation.browse.feed.FeedState
import reikai.presentation.browse.globalsearch.BrowseSearchRow
import reikai.presentation.browse.globalsearch.EntrySearchState
import reikai.presentation.browse.globalsearch.GlobalSearchEngine
import reikai.presentation.browse.migrate.BrowseMigrateRow
import reikai.presentation.browse.migrate.MigrateSourcesEngine
import reikai.presentation.browse.source.BrowseSourceRow
import reikai.presentation.browse.source.NovelSourcesFilterViewModel
import reikai.presentation.browse.source.SourcesEngine
import reikai.presentation.browse.source.SourcesListItem
import reikai.presentation.migrate.flow.EntryMigrationConfigViewModel
import reikai.presentation.migrate.flow.MigrationSourceIcon
import reikai.presentation.migrate.flow.MigrationSourceUi
import reikai.presentation.migrate.flow.loadingStrip
import reikai.presentation.migrate.flow.showsFormat

/**
 * Every list that shows novel sources names each one's packaging once it holds two kinds, so two
 * sources with one name (an app and an IReader copy of one site) can be told apart. When to name it
 * is NovelExtensionFormat.tellsApart's rule, pinned in its own test; each surface here is fed an app
 * and an IReader copy, so a surface handing the kernel the wrong list (or none) goes red. The feed's
 * add-source dialog passes its whole list straight to the kernel, so it has no case here.
 */
class SourceFormatLabelTest {

    @Test
    fun `a source's line reads its language, then its packaging`() {
        sourceDetail("English", "APK") shouldBe "English • APK"
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("surfaces")
    fun `each surface hands the kernel every source it shows`(name: String, showsFormat: () -> Boolean) {
        showsFormat() shouldBe true
    }

    companion object {
        @JvmStatic
        fun surfaces() = listOf(
            Arguments.of(
                "global search",
                { GlobalSearchEngine.State(rows = listOf(searchRow(APK), searchRow(IREADER))).showsFormat },
            ),
            Arguments.of(
                "the Sources tab",
                { SourcesEngine.State(items = listOf(sourceItem(APK), sourceItem(IREADER))).showsFormat },
            ),
            Arguments.of(
                "the sources filter",
                {
                    NovelSourcesFilterViewModel.State.Success(
                        items = listOf("en" to listOf(novelSource(APK), novelSource(IREADER))),
                        disabledSources = emptySet(),
                        disabledLanguages = emptySet(),
                    ).showsFormat
                },
            ),
            Arguments.of("the feed", { FeedState(entries = listOf(feedEntry(APK), feedEntry(IREADER))).showsFormat }),
            Arguments.of(
                "the migrate list",
                { MigrateSourcesEngine.State(items = listOf(migrateRow(APK), migrateRow(IREADER))).showsFormat },
            ),
            Arguments.of(
                "the migration source picker, counting both of its sections",
                {
                    EntryMigrationConfigViewModel.State(
                        selected = listOf(pickerSource(APK)),
                        available = listOf(pickerSource(IREADER)),
                    ).showsFormat
                },
            ),
            Arguments.of(
                "a migration search",
                { listOf(pickerSource(APK).loadingStrip(), pickerSource(IREADER).loadingStrip()).showsFormat },
            ),
        )

        private fun searchRow(format: NovelExtensionFormat) = BrowseSearchRow(
            key = SourceKey.Novel("s-$format"),
            name = "Site",
            lang = "en",
            isPinned = false,
            state = EntrySearchState.Loading,
            source = Unit,
            format = format,
        )

        private fun sourceItem(format: NovelExtensionFormat) = SourcesListItem.Row(
            BrowseSourceRow(
                key = SourceKey.Novel("s-$format"),
                name = "Site",
                lang = "en",
                isPinned = false,
                isUsedLast = false,
                supportsLatest = true,
                extensionName = "Site",
                contentWarning = ContentWarning.SAFE,
                source = Unit,
                format = format,
            ),
        )

        private fun novelSource(format: NovelExtensionFormat) = mockk<NovelSource> {
            every { this@mockk.format } returns format
        }

        private fun feedEntry(format: NovelExtensionFormat) = FeedEntry(
            feedId = format.ordinal.toLong(),
            savedSearch = null,
            row = searchRow(format),
            sourceName = "Site",
            listing = EntryBrowseListing.Latest,
        )

        private fun migrateRow(format: NovelExtensionFormat) = BrowseMigrateRow(
            key = SourceKey.Novel("s-$format"),
            name = "Site",
            lang = "en",
            count = 1,
            isStub = false,
            source = Unit,
            format = format,
        )

        private fun pickerSource(format: NovelExtensionFormat) = MigrationSourceUi(
            key = "s-$format",
            name = "Site",
            lang = "en",
            icon = MigrationSourceIcon.NovelUrl(null),
            format = format,
        )
    }
}
