package eu.kanade.presentation.more.settings.screen

import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import reikai.domain.library.ContentType

class ContentTypedScreensTest {

    @ParameterizedTest
    @MethodSource("typed")
    fun `a typed screen leaves with its type`(screen: Any, type: ContentType) {
        val other = ContentType.entries.first { it != ContentType.ALL && it != type }

        other.showsScreen(screen) shouldBe false
    }

    @ParameterizedTest
    @MethodSource("typed")
    fun `a typed screen stays with its type and under All`(screen: Any, type: ContentType) {
        listOf(type.showsScreen(screen), ContentType.ALL.showsScreen(screen)) shouldBe listOf(true, true)
    }

    @org.junit.jupiter.api.Test
    fun `a screen of no type stays under every value`() {
        ContentType.entries.map { it.showsScreen(SettingsAdvancedScreen) } shouldBe listOf(true, true, true)
    }

    companion object {
        @JvmStatic
        fun typed() = contentTypedScreens.map { (screen, type) ->
            org.junit.jupiter.params.provider.Arguments.of(screen, type)
        }
    }
}
