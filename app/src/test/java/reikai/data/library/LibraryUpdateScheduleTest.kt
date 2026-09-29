package reikai.data.library

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI

class LibraryUpdateScheduleTest {

    @Test
    fun `a scheduled update waits while a manual one runs`() {
        shouldDeferAutoUpdate(sdkInt = 34, emptySet(), isOnWifi = { true }, isManualRunning = true) shouldBe true
    }

    @Test
    fun `a wifi-only scheduled update below Android 9 waits for wifi`() {
        shouldDeferAutoUpdate(sdkInt = 27, WIFI_ONLY, isOnWifi = { false }, isManualRunning = false) shouldBe true
    }

    @Test
    fun `a wifi-only scheduled update below Android 9 runs on wifi`() {
        shouldDeferAutoUpdate(sdkInt = 27, WIFI_ONLY, isOnWifi = { true }, isManualRunning = false) shouldBe false
    }

    @Test
    fun `from Android 9 a wifi-only scheduled update leaves wifi to the network constraint`() {
        shouldDeferAutoUpdate(sdkInt = 28, WIFI_ONLY, isOnWifi = { false }, isManualRunning = false) shouldBe false
    }

    @Test
    fun `from Android 9 the wifi state is never read`() {
        val unreadable = { error("the Wi-Fi state was read") }

        shouldDeferAutoUpdate(sdkInt = 28, WIFI_ONLY, unreadable, isManualRunning = false) shouldBe false
    }

    private companion object {
        val WIFI_ONLY = setOf(DEVICE_ONLY_ON_WIFI)
    }
}
