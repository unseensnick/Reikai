package reikai.data.notification

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ShownEntryNameTest {

    @Test
    fun `a notification names the series while content is shown`() {
        shownEntryName("A Novel", hideContent = false) shouldBe "A Novel"
    }

    @Test
    fun `a notification names nothing while content is hidden`() {
        shownEntryName("A Novel", hideContent = true) shouldBe null
    }
}
