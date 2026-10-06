package reikai.novel.update

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.data.notification.extensionUpdatesNoticeText

/**
 * The plugin update notice is the extension one: with Hide notification content on, it names no
 * plugin, only how many have an update.
 */
class LnPluginUpdateNoticeTest {

    @Test
    fun `hidden notification content names no plugin`() {
        extensionUpdatesNoticeText(listOf("Alpha", "Beta"), hideContent = true) shouldBe null
    }

    @Test
    fun `shown notification content lists the plugins`() {
        extensionUpdatesNoticeText(listOf("Alpha", "Beta"), hideContent = false) shouldBe "Alpha, Beta"
    }
}
