package reikai.data.coil

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.source.NovelIconHints

class ExtensionIconTest {

    /** Fetched, it would come back to the fetcher that asked, which asks again without end. */
    @Test
    fun `an icon listed in the app's own icon address is not borrowed`() {
        val hints = NovelIconHints(packages = mapOf("ireader.a.en" to extensionIconUrl("ireader.b.en")))

        borrowableIcons(hints, "ireader.a.en", emptyList()) shouldBe emptyList()
    }

    @Test
    fun `an icon on the web is borrowed`() {
        val hints = NovelIconHints(packages = mapOf("ireader.a.en" to "https://store/icon.png"))

        borrowableIcons(hints, "ireader.a.en", emptyList()) shouldBe listOf("https://store/icon.png")
    }
}
