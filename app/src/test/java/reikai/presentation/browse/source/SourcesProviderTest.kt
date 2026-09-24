package reikai.presentation.browse.source

import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.CatalogueSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.ui.browse.source.SourcesViewModel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.domain.library.ContentType
import reikai.novel.source.TachiyomiNovelSource
import tachiyomi.domain.source.model.Source

/** Both halves of the Sources list read a source's extension the same way: its name and its warning. */
class SourcesProviderTest {

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a flagged extension's source row carries its warning`(type: ContentType) = runTest {
        provider(type).rows.first()!!.single().contentWarning shouldBe ContentWarning.NSFW
    }

    @ParameterizedTest
    @EnumSource(ContentType::class, names = ["MANGA", "NOVELS"])
    fun `a source row names the extension that installed it`(type: ContentType) = runTest {
        provider(type).rows.first()!!.single().title shouldBe "Site (Pack)"
    }

    private fun provider(type: ContentType): SourcesProvider = when (type) {
        ContentType.NOVELS -> NovelSourcesProvider(
            mockk<NovelSourcesViewModel>(relaxed = true) {
                val source = TachiyomiNovelSource(catalogue(), pack(Extension.Kind.TACHIYOMI_NOVEL))
                every { sources } returns flowOf(listOf(NovelSourceEntry(source, isPinned = false, isUsedLast = false)))
            },
        )
        else -> MangaSourcesProvider(
            mockk<SourcesViewModel>(relaxed = true) {
                every { sources } returns flowOf(listOf(Source(SOURCE_ID, "en", "Site", false, false)))
            },
            flowOf(listOf(pack(Extension.Kind.MANGA, sources = listOf(catalogue())))),
        )
    }

    private fun catalogue() = mockk<CatalogueSource> {
        every { id } returns SOURCE_ID
        every { name } returns "Site"
        every { lang } returns "en"
        every { supportsLatest } returns false
        every { getFilterList() } returns FilterList()
    }

    private fun pack(kind: Extension.Kind, sources: List<CatalogueSource> = emptyList()) = Extension.Loaded(
        name = "Pack",
        pkgName = "eu.kanade.tachiyomi.extension.en.pack",
        versionName = "1.4.1",
        versionCode = 1,
        libVersion = 1.4,
        lang = "en",
        contentWarning = ContentWarning.NSFW,
        isShared = true,
        kind = kind,
        pkgFactory = null,
        sources = sources,
        icon = null,
    )

    private companion object {
        const val SOURCE_ID = 7L
    }
}
