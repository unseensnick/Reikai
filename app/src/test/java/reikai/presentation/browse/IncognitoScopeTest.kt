package reikai.presentation.browse

import cafe.adriel.voyager.core.screen.Screen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.source.SourceKey
import reikai.presentation.browse.catalogue.EntryCatalogueScreen
import reikai.presentation.novel.details.NovelScreen

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

    companion object {
        @JvmStatic
        fun cases() = listOf(
            arrayOf("novel from a source", NovelScreen("s", "u", fromSource = true), true),
            arrayOf("novel from the library", NovelScreen("s", "u"), false),
            arrayOf("manga from a source", MangaScreen(1L, fromSource = true), true),
            arrayOf("manga from the library", MangaScreen(1L), false),
            arrayOf("manga catalogue", EntryCatalogueScreen(SourceKey.Manga(1L)), true),
            arrayOf("novel catalogue", EntryCatalogueScreen(SourceKey.Novel("s")), true),
            arrayOf("home", HomeScreen, false),
        )
    }
}
