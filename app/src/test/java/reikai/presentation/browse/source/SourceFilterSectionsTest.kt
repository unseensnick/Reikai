package reikai.presentation.browse.source

import eu.kanade.tachiyomi.ui.browse.source.SourcesFilterViewModel
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.novel.source.NovelExtensionFormat
import reikai.novel.source.NovelSource
import tachiyomi.domain.source.model.Source

/**
 * The sources filter list, pinned once for both content types. The two keep different storage (a
 * manga language is off unless listed, a novel language on unless listed; ruled in
 * content-layer-browse-surface.md), so each probe stores the switch its own way and the cases read
 * the one list both halves draw.
 */
class SourceFilterSectionsTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a language switched on lists its sources`(probe: FilterProbe) {
        probe.sections(languageOn = true).single().let { it.enabled to it.sources.size } shouldBe (true to 1)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a language switched off lists none of its sources`(probe: FilterProbe) {
        probe.sections(languageOn = false).single().let { it.enabled to it.sources.size } shouldBe (false to 0)
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("probes")
    fun `a source switched off reads unchecked`(probe: FilterProbe) {
        probe.sections(languageOn = true, sourceOn = false).single().sources.single().checked shouldBe false
    }

    companion object {
        @JvmStatic
        fun probes() = listOf(MangaFilterProbe(), NovelFilterProbe())
    }
}

/** One content type's filter state for one language holding one source, stored the type's own way. */
interface FilterProbe {
    fun sections(languageOn: Boolean, sourceOn: Boolean = true): List<SourceFilterSection<*>>
}

class MangaFilterProbe : FilterProbe {
    private val source = Source(id = 1L, lang = "en", name = "manga", supportsLatest = false, isStub = false)

    override fun toString() = "manga"
    override fun sections(languageOn: Boolean, sourceOn: Boolean) = SourcesFilterViewModel.State.Success(
        items = sortedMapOf("en" to listOf(source)),
        enabledLanguages = if (languageOn) setOf("en") else emptySet(),
        disabledSources = if (sourceOn) emptySet() else setOf("${source.id}"),
    ).toSections()
}

class NovelFilterProbe : FilterProbe {
    private val source = mockk<NovelSource> {
        every { id } returns "plugin"
        every { format } returns NovelExtensionFormat.JS
    }

    override fun toString() = "novel"
    override fun sections(languageOn: Boolean, sourceOn: Boolean) = NovelSourcesFilterViewModel.State.Success(
        items = listOf("en" to listOf(source)),
        disabledSources = if (sourceOn) emptySet() else setOf(source.id),
        disabledLanguages = if (languageOn) emptySet() else setOf("en"),
    ).toSections()
}
