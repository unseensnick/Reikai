package reikai.novel.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelDownloadProgressTest {

    @Test
    fun `a paused status still shows while notification content is hidden`() {
        NovelDownloadProgress.Paused(1, 4, "No network connection available")
            .shownText(hideAll = true, hideAdult = true) shouldBe "No network connection available"
    }

    @Test
    fun `a downloading series is unnamed while notification content is hidden`() {
        NovelDownloadProgress.Downloading(1, 4, "A Novel", isAdult = false)
            .shownText(hideAll = true, hideAdult = false) shouldBe null
    }

    @Test
    fun `a downloading adult series is unnamed while adult content is hidden`() {
        NovelDownloadProgress.Downloading(1, 4, "A Novel", isAdult = true)
            .shownText(hideAll = false, hideAdult = true) shouldBe null
    }

    @Test
    fun `a downloading series is named while content is shown`() {
        NovelDownloadProgress.Downloading(1, 4, "A Novel", isAdult = true)
            .shownText(hideAll = false, hideAdult = false) shouldBe "A Novel"
    }
}
