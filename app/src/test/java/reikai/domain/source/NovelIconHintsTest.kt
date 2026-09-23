package reikai.domain.source

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** Icons for a novel app whose own icon shows nothing: its store's, then another listing's for its site. */
class NovelIconHintsTest {

    @Test
    fun `a site's host ignores case and www`() {
        siteHost("https://WWW.NovelFull.com/") shouldBe "novelfull.com"
    }

    @Test
    fun `an empty site has no host`() {
        siteHost("").shouldBeNull()
    }

    @Test
    fun `the store's icon for the app comes before a site's`() {
        hints.candidatesFor("ireader.novelfull.en", listOf("https://novelfull.com")) shouldBe
            listOf(STORE_ICON, SITE_ICON)
    }

    @Test
    fun `an app its store gives no icon takes the icon listed for its site`() {
        hints.candidatesFor("ireader.other.en", listOf("https://www.novelfull.com/")) shouldBe listOf(SITE_ICON)
    }

    @Test
    fun `an app on an unlisted site with no store icon has nothing to borrow`() {
        hints.candidatesFor("ireader.other.en", listOf("https://elsewhere.com")).shouldBeEmpty()
    }

    @Test
    fun `a listing entry with no site gives no site icon`() {
        NovelIconHints().plus(emptyMap(), listOf("" to SITE_ICON)).sites shouldBe emptyMap()
    }

    @Test
    fun `a newer listing replaces a site's icon`() {
        hints.plus(emptyMap(), listOf("https://novelfull.com" to "new.png")).sites["novelfull.com"] shouldBe "new.png"
    }

    private companion object {
        const val STORE_ICON = "https://store/icon/ireader-en-novelfull-v2.9.png"
        const val SITE_ICON = "https://plugins/novelfull/icon.png"

        val hints = NovelIconHints().plus(
            packages = mapOf("ireader.novelfull.en" to STORE_ICON),
            siteIcons = listOf("https://novelfull.com/" to SITE_ICON),
        )
    }
}
