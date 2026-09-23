package reikai.domain.track

import eu.kanade.tachiyomi.data.track.EnhancedTracker
import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

class ServerProgressTest {

    private val server = mockk<Tracker>(moreInterfaces = arrayOf(EnhancedTracker::class))

    @Test
    fun `a server is not sent a progress of nothing read`() {
        sendsProgressTo(server, 0.0) shouldBe false
    }

    @Test
    fun `a server is sent progress once a chapter is read`() {
        sendsProgressTo(server, 1.0) shouldBe true
    }

    @Test
    fun `a list tracker is sent a progress of nothing read, which it records as none`() {
        sendsProgressTo(mockk<Tracker>(), 0.0) shouldBe true
    }
}
