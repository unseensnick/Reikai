package reikai.presentation.browse

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueRoute
import reikai.presentation.novel.details.NovelRoute

class IncognitoScopeTest {

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    fun `a screen opened from a source closes when incognito ends, and no other does`(
        @Suppress("UNUSED_PARAMETER") name: String,
        screen: Screen,
        closes: Boolean,
    ) {
        screen.closesWhenIncognitoEnds() shouldBe closes
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    fun `only a catalogue names the source whose incognito applies`(
        @Suppress("UNUSED_PARAMETER") name: String,
        screen: Screen,
        source: SourceKey?,
    ) {
        screen.incognitoSource() shouldBe source
    }

    companion object {
        @JvmStatic
        fun sources() = listOf(
            arrayOf("manga catalogue", EntryCatalogueRoute(SourceKey.Manga(1L)), SourceKey.Manga(1L)),
            arrayOf("novel catalogue", EntryCatalogueRoute(SourceKey.Novel("s")), SourceKey.Novel("s")),
            arrayOf("manga from a source", MangaScreen(1L, fromSource = true), null),
            arrayOf("home", HomeScreen, null),
        )

        @JvmStatic
        fun cases() = listOf(
            arrayOf("novel from a source", NovelRoute(1L, fromSource = true), true),
            arrayOf("novel from the library", NovelRoute(1L), false),
            arrayOf("manga from a source", MangaScreen(1L, fromSource = true), true),
            arrayOf("manga from the library", MangaScreen(1L), false),
            arrayOf("manga catalogue", EntryCatalogueRoute(SourceKey.Manga(1L)), true),
            arrayOf("novel catalogue", EntryCatalogueRoute(SourceKey.Novel("s")), true),
            arrayOf("home", HomeScreen, false),
        )
    }
}
