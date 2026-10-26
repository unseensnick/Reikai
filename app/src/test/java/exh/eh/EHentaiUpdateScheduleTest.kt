package exh.eh

import androidx.work.BackoffPolicy
import io.kotest.matchers.shouldBe
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import tachiyomi.domain.library.service.LibraryPreferences.Companion.DEVICE_ONLY_ON_WIFI

/** The gallery checker's schedule, which builds its NetworkRequest through the framework. */
@RunWith(RobolectricTestRunner::class)
class EHentaiUpdateScheduleTest {

    @Test
    fun `the gallery checker retries a deferred run on the library update's linear backoff`() {
        EHentaiUpdateWorker.updateRequest(12, setOf(DEVICE_ONLY_ON_WIFI)).workSpec.backoffPolicy shouldBe
            BackoffPolicy.LINEAR
    }

    @Test
    fun `the scheduled check carries the tag that lets it wait for wifi`() {
        (EHentaiUpdateWorker.AUTO_TAG in EHentaiUpdateWorker.updateRequest(12, emptySet()).tags) shouldBe true
    }
}
