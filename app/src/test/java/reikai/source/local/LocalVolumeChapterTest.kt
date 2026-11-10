package reikai.source.local

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class LocalVolumeChapterTest {

    @Test
    fun `a chapter in a volume folder reads as series, volume and file`() {
        localVolumeChapter("Saga/Vol 01/Ch 1.cbz") shouldBe LocalVolumeChapter("Saga", "Vol 01", "Ch 1.cbz")
    }

    @Test
    fun `a volume's Extras chapter has an empty file`() {
        localVolumeChapter("Saga/Vol 01/")?.file shouldBe ""
    }

    @Test
    fun `a chapter in the series folder is not in a volume`() {
        localVolumeChapter("Saga/Ch 1.cbz") shouldBe null
    }

    @Test
    fun `a moved chapter writes back the same address shape`() {
        localVolumeChapter("Saga/Vol 01/Ch 1.cbz")!!.copy(series = "New").url shouldBe "New/Vol 01/Ch 1.cbz"
    }
}
