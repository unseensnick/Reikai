package reikai.novel.local

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.novel.local.epub.EpubMetadata

/** The order a local series reads its volumes in, and the number each one is labelled with. */
class VolumeOrderTest {

    private fun order(vararg volumes: Pair<String, Double?>): List<String> =
        inVolumeOrder(volumes.toList(), { it.first }) { it.second }.map { it.first }

    private fun meta(seriesIndex: Double?, isReikaiExport: Boolean) = EpubMetadata(
        title = null,
        author = null,
        description = null,
        genres = emptyList(),
        language = null,
        series = null,
        seriesIndex = seriesIndex,
        artist = null,
        status = null,
        isReikaiExport = isReikaiExport,
    )

    @Test
    fun `volumes that all carry a series number read in that order`() {
        order("a.epub" to 3.0, "b.epub" to 1.0, "c.epub" to 2.0) shouldBe listOf("b.epub", "c.epub", "a.epub")
    }

    @Test
    fun `a volume without a number keeps the whole series in file name order`() {
        order("Vol 10.epub" to 1.0, "Vol 2.epub" to null, "Vol 1.epub" to 2.0) shouldBe
            listOf("Vol 1.epub", "Vol 2.epub", "Vol 10.epub")
    }

    @Test
    fun `a decimal series number sits between its neighbours`() {
        order("a.epub" to 2.0, "b.epub" to 1.5, "c.epub" to 1.0) shouldBe listOf("c.epub", "b.epub", "a.epub")
    }

    @Test
    fun `volumes with the same number read in file name order`() {
        order("b 10.epub" to 1.0, "b 2.epub" to 1.0) shouldBe listOf("b 2.epub", "b 10.epub")
    }

    @Test
    fun `a book's series number labels its volume`() {
        LocalNovelSource.volumeLabel(0, meta(1.5, isReikaiExport = false)) shouldBe "1.5"
    }

    @Test
    fun `a Reikai export is labelled by its place in the series, not where its chapters start`() {
        LocalNovelSource.volumeLabel(1, meta(51.0, isReikaiExport = true)) shouldBe "2"
    }
}
