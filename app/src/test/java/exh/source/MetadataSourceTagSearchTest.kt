package exh.source

import eu.kanade.tachiyomi.source.online.MetadataSource
import eu.kanade.tachiyomi.source.online.all.AsmHentai
import eu.kanade.tachiyomi.source.online.all.EHentai
import eu.kanade.tachiyomi.source.online.all.HentaiFox
import eu.kanade.tachiyomi.source.online.all.Koharu
import eu.kanade.tachiyomi.source.online.all.Lanraragi
import eu.kanade.tachiyomi.source.online.all.MangaDex
import eu.kanade.tachiyomi.source.online.all.NHentai
import eu.kanade.tachiyomi.source.online.all.NHentaiNet
import eu.kanade.tachiyomi.source.online.english.EightMuses
import eu.kanade.tachiyomi.source.online.english.Pururin
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/** Every metadata source answers a tag chip's search in its own grammar. */
class MetadataSourceTagSearchTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    fun `a tag chip searches in the source's own grammar`(
        name: String,
        source: MetadataSource<*, *>,
        namespace: String,
        tag: String,
        expected: String,
    ) {
        source.tagSearchQuery(namespace, tag) shouldBe expected
    }

    companion object {
        // The sources' constructors reach the network stack, so the real grammar runs on a bare instance.
        private inline fun <reified T : MetadataSource<*, *>> grammarOf(): T =
            mockk { every { tagSearchQuery(any(), any()) } answers { callOriginal() } }

        @JvmStatic
        fun sources() = listOf(
            Arguments.of("E-Hentai", grammarOf<EHentai>(), "female", "big breasts", "female:\"big breasts$\""),
            Arguments.of("Lanraragi", grammarOf<Lanraragi>(), "artist", "someone", "artist:someone$"),
            Arguments.of("installed nhentai", grammarOf<NHentai>(), "tag", "big breasts", "\"big breasts\""),
            Arguments.of("built-in nhentai", grammarOf<NHentaiNet>(), "tag", "big breasts", "\"big breasts\""),
            Arguments.of("MangaDex", grammarOf<MangaDex>(), "Tags", "Romance", "Romance"),
            Arguments.of("Pururin", grammarOf<Pururin>(), "tag", "big breasts", "big breasts"),
            Arguments.of("HentaiFox", grammarOf<HentaiFox>(), "tag", "big breasts", "big breasts"),
            Arguments.of("AsmHentai", grammarOf<AsmHentai>(), "tag", "big breasts", "big breasts"),
            Arguments.of("Koharu", grammarOf<Koharu>(), "tag", "big breasts", "big breasts"),
            Arguments.of("8muses", grammarOf<EightMuses>(), "tag", "big breasts", "big breasts"),
        )
    }
}
