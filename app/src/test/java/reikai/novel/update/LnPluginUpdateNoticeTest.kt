package reikai.novel.update

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * The plugin update notice follows Mihon's extension notice: with Hide notification content on, it
 * names no plugin, only how many have an update.
 */
class LnPluginUpdateNoticeTest {

    @Test
    fun `hidden notification content names no plugin`() {
        pluginUpdateNoticeText(listOf("Alpha", "Beta"), hideContent = true) shouldBe null
    }

    @Test
    fun `shown notification content lists the plugins`() {
        pluginUpdateNoticeText(listOf("Alpha", "Beta"), hideContent = false) shouldBe "Alpha, Beta"
    }
}
