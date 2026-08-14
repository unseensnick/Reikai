package reikai.domain.manga

import eu.kanade.tachiyomi.extension.ExtensionManager
import eu.kanade.tachiyomi.extension.model.Extension
import eu.kanade.tachiyomi.source.Source
import eu.kanade.tachiyomi.source.online.NamespaceSource
import exh.source.MANGADEX_IDS
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
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

    private fun checker(source: Source?, nsfwSourceId: Long? = null): AdultContentChecker {
        val extensions = nsfwSourceId?.let { sid ->
            val extSource = mockk<Source>()
            every { extSource.id } returns sid
            val extension = mockk<Extension.Installed>()
            every { extension.isNsfw } returns true
            every { extension.sources } returns listOf(extSource)
            listOf(extension)
        }.orEmpty()

        val extensionManager = mockk<ExtensionManager>()
        every { extensionManager.installedExtensionsFlow } returns MutableStateFlow(extensions)
        val sourceManager = mockk<SourceManager>()
        every { sourceManager.get(any()) } answers { source }
        return AdultContentChecker(extensionManager, sourceManager)
    }

    /** What every built-in gallery source is: namespaced, so each chapter is a standalone work. */
    private fun namespacedSource(name: String): Source {
        val source = mockk<NamespaceSource>()
        every { source.name } returns name
        return source
    }

    @Test
    fun `flags a built-in gallery source as adult`() {
        checker(source = namespacedSource("Some Gallery")).isAdult(manga(1L)) shouldBe true
    }

    /**
     * MangaDex is namespaced like every gallery source, so a check that keyed on that alone hid every
     * MangaDex title behind the generic notification string. It is excluded by source id.
     */
    @Test
    fun `does not flag MangaDex, which is namespaced but not a gallery`() {
        checker(source = namespacedSource("MangaDex"))
            .isAdult(manga(MANGADEX_IDS.first(), genre = listOf("Action"))) shouldBe false
    }

    @Test
    fun `flags a manga from an NSFW-flagged extension as adult`() {
        // A plain, non-adult-named source, but its extension is NSFW-flagged.
        checker(source = plainSource("Some Reader"), nsfwSourceId = 42L).isAdult(manga(42L)) shouldBe true
    }

    @Test
    fun `flags an adult source name via the heuristic`() {
        checker(source = plainSource("NHentai")).isAdult(manga(1L)) shouldBe true
    }

    @Test
    fun `flags an adult genre tag via the heuristic`() {
        checker(source = plainSource("Some Reader")).isAdult(manga(1L, genre = listOf("Hentai"))) shouldBe true
    }

    @Test
    fun `does not flag a normal manga on a safe source`() {
        checker(source = plainSource("MangaDex")).isAdult(manga(1L, genre = listOf("Action"))) shouldBe false
    }
}
