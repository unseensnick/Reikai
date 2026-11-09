package reikai.presentation.details

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/** How a local series' chapter list is grouped under its volumes, for both types' addresses. */
class EntryVolumesTest {

    private fun row(id: Long, volume: ChapterVolume?, read: Boolean = false) = EntryChapterListItem.Chapter(
        id = id,
        name = volume?.let { "${it.prefix}Ch $id" } ?: "Chapter $id",
        subtitle = null,
        read = read,
        bookmark = false,
        dateUpload = null,
        chapterNumber = id.toDouble(),
        progress = null,
        downloadState = Download.State.NOT_DOWNLOADED,
        numberHinted = false,
        volume = volume,
    )

    private val v1 = mangaChapterVolume(0L, "Series/Vol 01/Ch 001.cbz", "Vol 01 - Ch 001")!!
    private val v2 = mangaChapterVolume(0L, "Series/Vol 02/Ch 003.cbz", "Vol 02 - Ch 003")!!

    @Test
    fun `a local manga chapter in a volume folder names the folder and its number`() {
        v1 shouldBe ChapterVolume("Vol 01", VolumeLabel.Number(1.0), "Vol 01 - ")
    }

    @Test
    fun `a chapter of another source has no volume whatever its address`() {
        mangaChapterVolume(42L, "/manga/chapter/1", "Chapter 1") shouldBe null
    }

    @Test
    fun `a local novel chapter names the book its address names, labelled as the source names it`() {
        novelChapterVolume("Series::Book%202.epub::c1.xhtml::t", "Vol. 2 - Prologue") shouldBe
            ChapterVolume("Book 2.epub", VolumeLabel.Number(2.0), "Vol. 2 - ")
    }

    @Test
    fun `each volume gets a header with its read count, rows lose the volume in front`() {
        val view = listOf(row(1, v1, read = true), row(2, v1), row(3, v2)).inVolumes(picked = null)!!

        view.items.map {
            (it as? EntryChapterListItem.Chapter)?.name
                ?: (it as EntryChapterListItem.Volume).let { h -> "${h.key} ${h.readCount}" }
        } shouldBe
            listOf("Vol 01 1", "Ch 1", "Ch 2", "Vol 02 0", "Ch 3")
    }

    @Test
    fun `a picked volume shows alone`() {
        listOf(row(1, v1), row(3, v2)).inVolumes(picked = "Vol 02")!!.items.size shouldBe 2
    }

    @Test
    fun `a picked volume the series no longer has shows every volume`() {
        listOf(row(1, v1), row(3, v2)).inVolumes(picked = "Vol 09")!!.picked shouldBe null
    }

    @Test
    fun `chips follow the volume numbers whatever the chapter sort`() {
        listOf(row(3, v2), row(1, v1)).inVolumes(picked = null)!!.chips.map { it.key } shouldBe
            listOf("Vol 01", "Vol 02")
    }
}
