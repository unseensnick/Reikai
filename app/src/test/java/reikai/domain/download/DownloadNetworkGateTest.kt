package reikai.domain.download

import eu.kanade.tachiyomi.util.system.NetworkState
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.i18n.MR

/** Whether a network lets both downloaders fetch, and the reason they pause when it does not. */
class DownloadNetworkGateTest {

    @Test
    fun `offline pauses for want of a network even with Wi-Fi only on`() {
        downloadNetworkIssue(OFFLINE, wifiOnly = true) shouldBe MR.strings.download_notifier_no_network
    }

    @Test
    fun `mobile data with Wi-Fi only on pauses for want of Wi-Fi`() {
        downloadNetworkIssue(MOBILE, wifiOnly = true) shouldBe MR.strings.download_notifier_text_only_wifi
    }

    @Test
    fun `Wi-Fi with Wi-Fi only on downloads`() {
        downloadNetworkIssue(WIFI, wifiOnly = true) shouldBe null
    }

    @Test
    fun `mobile data with Wi-Fi only off downloads`() {
        downloadNetworkIssue(MOBILE, wifiOnly = false) shouldBe null
    }

    private companion object {
        val OFFLINE = NetworkState(isConnected = false, isValidated = false, isWifi = false)
        val MOBILE = NetworkState(isConnected = true, isValidated = true, isWifi = false)
        val WIFI = NetworkState(isConnected = true, isValidated = true, isWifi = true)
    }
}
