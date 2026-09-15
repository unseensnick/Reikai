package reikai.domain.manga

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.NamespaceSource
import exh.source.MANGADEX_IDS
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager

class AdultContentCheckerTest {

    private fun manga(sourceId: Long, genre: List<String>? = null): Manga =
        Manga.create().copy(id = 1L, source = sourceId, url = "u", title = "t", genre = genre)

    private fun plainSource(name: String): Source {
        val source = mockk<Source>()
        every { source.name } returns name
        return source
    }

    private fun nsfwExtensions(nsfwSourceId: Long?, warning: ContentWarning): List<Extension.Loaded> =
        nsfwSourceId?.let { sid ->
            val extSource = mockk<Source>()
            every { extSource.id } returns sid
            val extension = mockk<Extension.Loaded>()
            every { extension.contentWarning } returns warning
            every { extension.sources } returns listOf(extSource)
            listOf(extension)
        }.orEmpty()

    private fun checker(
        source: Source?,
        nsfwSourceId: Long? = null,
        warning: ContentWarning = ContentWarning.NSFW,
    ): AdultContentChecker {
        val extensionManager = mockk<ExtensionManager>()
        every { extensionManager.loadedExtensionsFlow } returns
            MutableStateFlow(nsfwExtensions(nsfwSourceId, warning))
        val sourceManager = mockk<SourceManager>()
        coEvery { sourceManager.get(any()) } answers { source }
        return AdultContentChecker(extensionManager, sourceManager)
    }

    /** A manager whose scan never finished, so its installed list never emits. */
    private fun stalledChecker(source: Source?): AdultContentChecker {
        val extensionManager = mockk<ExtensionManager>()
        every { extensionManager.loadedExtensionsFlow } returns MutableSharedFlow()
        val sourceManager = mockk<SourceManager>()
        coEvery { sourceManager.get(any()) } answers { source }
        return AdultContentChecker(extensionManager, sourceManager)
    }

    private suspend fun AdultContentChecker.isAdult(manga: Manga): Boolean =
        adultIdsAmong(listOf(manga)).contains(manga.id)

    /** What every built-in gallery source is: namespaced, so each chapter is a standalone work. */
    private fun namespacedSource(name: String): Source {
        val source = mockk<NamespaceSource>()
        every { source.name } returns name
        return source
    }

    @Test
    fun `flags a built-in gallery source as adult`() = runTest {
        checker(source = namespacedSource("Some Gallery")).isAdult(manga(1L)) shouldBe true
    }

    /**
     * MangaDex is namespaced like every gallery source, so a check that keyed on that alone hid every
     * MangaDex title behind the generic notification string. It is excluded by source id.
     */
    @Test
    fun `does not flag MangaDex, which is namespaced but not a gallery`() = runTest {
        checker(source = namespacedSource("MangaDex"))
            .isAdult(manga(MANGADEX_IDS.first(), genre = listOf("Action"))) shouldBe false
    }

    @Test
    fun `flags a manga from an extension warned as mixed as adult`() = runTest {
        // Mixed still hides the title: the check guards the lock screen, where a stray generic line is the safe miss.
        checker(source = plainSource("Some Reader"), nsfwSourceId = 42L, warning = ContentWarning.MIXED)
            .isAdult(manga(42L)) shouldBe true
    }

    @Test
    fun `does not flag a manga from an extension warned as safe`() = runTest {
        checker(source = plainSource("Some Reader"), nsfwSourceId = 42L, warning = ContentWarning.SAFE)
            .isAdult(manga(42L)) shouldBe false
    }

    @Test
    fun `flags a manga from an NSFW-flagged extension as adult`() = runTest {
        // A plain, non-adult-named source, but its extension is NSFW-flagged.
        checker(source = plainSource("Some Reader"), nsfwSourceId = 42L).isAdult(manga(42L)) shouldBe true
    }

    @Test
    fun `flags an adult source name via the heuristic`() = runTest {
        checker(source = plainSource("NHentai")).isAdult(manga(1L)) shouldBe true
    }

    @Test
    fun `flags an adult genre tag via the heuristic`() = runTest {
        checker(source = plainSource("Some Reader")).isAdult(manga(1L, genre = listOf("Hentai"))) shouldBe true
    }

    @Test
    fun `does not flag a normal manga on a safe source`() = runTest {
        checker(source = plainSource("MangaDex")).isAdult(manga(1L, genre = listOf("Action"))) shouldBe false
    }

    /**
     * Failing closed: an extension scan that never completes must not let a title through to the
     * lock screen. The caller only asks when the user turned the hide switch on.
     */
    @Test
    fun `treats every entry as adult when the extension list never arrives`() = runTest {
        stalledChecker(source = plainSource("MangaDex")).isAdult(manga(1L, genre = listOf("Action"))) shouldBe true
    }
}
