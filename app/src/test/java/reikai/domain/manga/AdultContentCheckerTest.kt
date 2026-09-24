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
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import mihon.domain.extension.model.ContentWarning
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.novel.model.Novel
import reikai.novel.source.NovelSource
import reikai.novel.source.NovelSourceManager
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

    private fun checker(source: Source?): AdultContentChecker {
        val extensionManager = mockk<ExtensionManager>()
        every { extensionManager.loadedExtensionsFlow } returns MutableStateFlow(emptyList())
        val sourceManager = mockk<SourceManager>()
        coEvery { sourceManager.get(any()) } answers { source }
        return AdultContentChecker(extensionManager, sourceManager, mockk())
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
    fun `flags an adult source name via the heuristic`() = runTest {
        checker(source = plainSource("NHentai")).isAdult(manga(1L)) shouldBe true
    }

    @Test
    fun `does not flag a normal manga on a safe source`() = runTest {
        checker(source = plainSource("MangaDex")).isAdult(manga(1L, genre = listOf("Action"))) shouldBe false
    }

    /**
     * The rules both content types share, run against each type's entry point. [warning] is the
     * installing extension's content warning; [stalled] is an extension scan that never finishes.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `an entry from an extension warned as NSFW is adult`(type: Type) = runTest {
        type.isAdult(warning = ContentWarning.NSFW) shouldBe true
    }

    // Mixed still hides the title: the check guards the lock screen, where a stray generic line is the safe miss.
    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `an entry from an extension warned as mixed is adult`(type: Type) = runTest {
        type.isAdult(warning = ContentWarning.MIXED) shouldBe true
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `an entry from an extension warned as safe is not adult`(type: Type) = runTest {
        type.isAdult(warning = ContentWarning.SAFE) shouldBe false
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `an adult genre tag makes an entry adult`(type: Type) = runTest {
        type.isAdult(warning = ContentWarning.SAFE, genre = listOf("Hentai")) shouldBe true
    }

    /**
     * Failing closed: an extension scan that never completes must not let a title through to the
     * lock screen. The caller only asks when the user turned the hide switch on.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    fun `every entry is adult when the extension list never arrives`(type: Type) = runTest {
        type.isAdult(warning = ContentWarning.SAFE, stalled = true) shouldBe true
    }

    /** A plugin carries no warning and is not an installed app, so only its genres can mark it. */
    @Test
    fun `a novel from an LN plugin is judged by its genres alone`() = runTest {
        val novelSources = mockk<NovelSourceManager> { coEvery { getWithoutPlugins(any()) } returns null }
        val checker = AdultContentChecker(mockk(), mockk(), novelSources)

        checker.adultNovelIdsAmong(listOf(novel("plugin", genre = listOf("Fantasy")))) shouldBe emptySet()
    }

    enum class Type {
        MANGA {
            override suspend fun isAdult(warning: ContentWarning, genre: List<String>?, stalled: Boolean): Boolean {
                val extensionManager = mockk<ExtensionManager> {
                    every { loadedExtensionsFlow } returns if (stalled) {
                        MutableSharedFlow()
                    } else {
                        MutableStateFlow(extensions(warning, SOURCE_ID))
                    }
                }
                val sourceManager = mockk<SourceManager>()
                val source = mockk<Source> { every { name } returns "Some Reader" }
                coEvery { sourceManager.get(any()) } returns source
                val entry = Manga.create().copy(id = 1L, source = SOURCE_ID, url = "u", title = "t", genre = genre)
                return 1L in AdultContentChecker(extensionManager, sourceManager, mockk()).adultIdsAmong(listOf(entry))
            }
        },
        NOVEL {
            override suspend fun isAdult(warning: ContentWarning, genre: List<String>?, stalled: Boolean): Boolean {
                val source = mockk<NovelSource> { every { contentWarning } returns warning }
                val novelSources = mockk<NovelSourceManager> {
                    if (stalled) {
                        coEvery { getWithoutPlugins(any()) } coAnswers { awaitCancellation() }
                    } else {
                        coEvery { getWithoutPlugins("tachiyomi:$SOURCE_ID") } returns source
                    }
                }
                val entry = novel("tachiyomi:$SOURCE_ID", genre)
                return 1L in AdultContentChecker(mockk(), mockk(), novelSources).adultNovelIdsAmong(listOf(entry))
            }
        },
        ;

        abstract suspend fun isAdult(
            warning: ContentWarning,
            genre: List<String>? = listOf("Action"),
            stalled: Boolean = false,
        ): Boolean
    }

    companion object {
        private const val SOURCE_ID = 42L

        @JvmStatic
        fun types() = Type.entries

        private fun novel(source: String, genre: List<String>?): Novel =
            Novel.create().copy(id = 1L, source = source, url = "u", title = "t", genre = genre)

        private fun extensions(warning: ContentWarning, sourceId: Long): List<Extension.Loaded> {
            val extSource = mockk<Source> { every { id } returns sourceId }
            return listOf(
                mockk<Extension.Loaded> {
                    every { contentWarning } returns warning
                    every { sources } returns listOf(extSource)
                },
            )
        }
    }
}
