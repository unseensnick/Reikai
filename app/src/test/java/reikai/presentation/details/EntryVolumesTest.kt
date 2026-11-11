package reikai.presentation.details

import eu.kanade.tachiyomi.data.download.model.Download
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.chapter.LocalVolume
import reikai.domain.chapter.LooseFiles
import tachiyomi.domain.manga.model.Manga

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

    private companion object {
        val LOCAL = Manga.create().copy(source = 0L, url = "Series")
        val ONLINE = Manga.create().copy(source = 42L, url = "/manga/1")
    }

    private val v1 = mangaChapterVolume(LOCAL, "Series/Vol 01/Ch 001.cbz", "Vol 01 - Ch 001")!!
    private val v2 = mangaChapterVolume(LOCAL, "Series/Vol 02/Ch 003.cbz", "Vol 02 - Ch 003")!!

    @Test
    fun `a local manga chapter in a volume folder names the folder and its number`() {
        v1 shouldBe ChapterVolume("Series", "Vol 01", VolumeLabel.Number(1.0), "Vol 01 - ")
    }

    @Test
    fun `a volume a group lists from another folder goes by that folder and its own name`() {
        mangaChapterVolume(LOCAL, "Other/Vol 01/Ch 001.cbz", "Vol 01 - Ch 001") shouldBe
            ChapterVolume("Series", "Other/Vol 01", VolumeLabel.Number(1.0), "Vol 01 - ")
    }

    @Test
    fun `another folder's loose chapters are one volume, led by the folder's name`() {
        mangaChapterVolume(LOCAL, "Other/Ch 001.cbz", "Other - Ch 001") shouldBe
            ChapterVolume("Series", "Other/", VolumeLabel.Name("Other"), "Other - ", "Ch 001.cbz", "Ch 001")
    }

    @Test
    fun `an organized volume shows its label, not the series name Organize files put in front of it`() {
        val titled = LOCAL.copy(title = "Alpha: Saga", url = "Alpha (2)")
        listOf(
            mangaChapterVolume(titled, "Alpha (2)/Alpha_ Saga - Beta/Ch 1", "Alpha_ Saga - Beta - Ch 1")!!.label,
            mangaChapterVolume(titled, "Alpha (2)/Alpha (2) - Vol. 2/Ch 1", "Alpha (2) - Vol. 2 - Ch 1")!!.label,
            mangaChapterVolume(titled, "Other/Alpha_ Saga - Beta/Ch 1", "Alpha_ Saga - Beta - Ch 1")!!.label,
        ) shouldBe listOf(VolumeLabel.Name("Beta"), VolumeLabel.Number(2.0), VolumeLabel.Name("Alpha_ Saga - Beta"))
    }

    @Test
    fun `a series' own loose chapters default to Extras, another folder's to that folder`() {
        listOf(
            mangaChapterVolume(LOCAL, "Series/Ch 1.cbz", "Ch 1")!!.label,
            mangaChapterVolume(LOCAL, "Other/Ch 1.cbz", "Other - Ch 1")!!.label,
        ) shouldBe listOf(VolumeLabel.Loose, VolumeLabel.Name("Other"))
    }

    private val loose1 = mangaChapterVolume(LOCAL, "Series/Ch 010.cbz", "Ch 010")!!
    private val loose2 = mangaChapterVolume(LOCAL, "Series/Ch 011.cbz", "Ch 011")!!
    private val mixed = listOf(row(1, v1), row(10, loose1), row(11, loose2))

    private fun headers(view: VolumeView?) = view?.items?.filterIsInstance<EntryChapterListItem.Volume>()?.map {
        it.key
    }

    @Test
    fun `a manga's loose files show as one volume by default`() {
        headers(mixed.inVolumes(null, emptyMap(), emptyList())) shouldBe listOf("Vol 01", "")
    }

    @Test
    fun `a manga's loose files can each show as a volume of their own`() {
        val loose = mapOf("Series" to LooseFiles.EACH_ITS_OWN)

        headers(mixed.inVolumes(null, emptyMap(), emptyList(), loose)) shouldBe
            listOf("Vol 01", "Ch 010.cbz", "Ch 011.cbz")
    }

    @Test
    fun `another folder combined in whole stays one volume when the series' own loose files are each their own`() {
        val borrowed = listOf(
            row(20, mangaChapterVolume(LOCAL, "Beta/Ch 1", "Beta - Ch 1")),
            row(21, mangaChapterVolume(LOCAL, "Beta/Ch 2", "Beta - Ch 2")),
        )
        val loose = mapOf("Series" to LooseFiles.EACH_ITS_OWN)

        headers((mixed + borrowed).inVolumes(null, emptyMap(), emptyList(), loose)) shouldBe
            listOf("Vol 01", "Ch 010.cbz", "Ch 011.cbz", "Beta/")
    }

    @Test
    fun `a manga whose loose files show in no volume is one flat list`() {
        mixed.inVolumes(null, emptyMap(), emptyList(), mapOf("Series" to LooseFiles.NO_VOLUME)) shouldBe null
    }

    @Test
    fun `a loose file of its own takes the name the user gave it`() {
        val saved = mapOf("Series" to listOf(LocalVolume("Ch 010.cbz", null, "Bonus")))

        mixed.inVolumes(null, saved, emptyList(), mapOf("Series" to LooseFiles.EACH_ITS_OWN))!!.chips.map {
            it.label
        } shouldBe
            listOf(VolumeLabel.Number(1.0), VolumeLabel.Name("Bonus"), VolumeLabel.Name("Ch 011"))
    }

    @Test
    fun `a book a group lists from another folder goes by that folder and its own name`() {
        novelChapterVolume("Saga", "Series::Book%202.epub::c1.xhtml::t", "Vol. 2 - Prologue") shouldBe
            ChapterVolume("Saga", "Series/Book 2.epub", VolumeLabel.Number(2.0), "Vol. 2 - ")
    }

    @Test
    fun `a chapter of another source has no volume whatever its address`() {
        mangaChapterVolume(ONLINE, "/manga/chapter/1", "Chapter 1") shouldBe null
    }

    @Test
    fun `a local novel chapter names the book its address names, labelled as the source names it`() {
        novelChapterVolume("Series", "Series::Book%202.epub::c1.xhtml::t", "Vol. 2 - Prologue") shouldBe
            ChapterVolume("Series", "Book 2.epub", VolumeLabel.Number(2.0), "Vol. 2 - ")
    }

    @Test
    fun `each volume gets a header with its read count, rows lose the volume in front`() {
        val view = listOf(
            row(1, v1, read = true),
            row(2, v1),
            row(3, v2),
        ).inVolumes(picked = null, saved = emptyMap(), every = emptyList())!!

        view.items.map {
            (it as? EntryChapterListItem.Chapter)?.name
                ?: (it as EntryChapterListItem.Volume).let { h -> "${h.key} ${h.readCount}" }
        } shouldBe
            listOf("Vol 01 1", "Ch 1", "Ch 2", "Vol 02 0", "Ch 3")
    }

    @Test
    fun `a picked volume shows alone`() {
        listOf(
            row(1, v1),
            row(3, v2),
        ).inVolumes(picked = "Vol 02", saved = emptyMap(), every = emptyList())!!.items.size shouldBe
            2
    }

    @Test
    fun `a picked volume the series no longer has shows every volume`() {
        listOf(
            row(1, v1),
            row(3, v2),
        ).inVolumes(picked = "Vol 09", saved = emptyMap(), every = emptyList())!!.picked shouldBe
            null
    }

    @Test
    fun `chips follow the volume numbers whatever the chapter sort`() {
        listOf(row(3, v2), row(1, v1)).inVolumes(picked = null, saved = emptyMap(), every = emptyList())!!.chips.map {
            it.key
        } shouldBe
            listOf("Vol 01", "Vol 02")
    }

    @Test
    fun `a volume the user named shows their name`() {
        val saved = mapOf("Series" to listOf(LocalVolume("Vol 02", position = null, label = "Finale")))

        listOf(row(1, v1), row(3, v2)).inVolumes(picked = null, saved, every = emptyList())!!.chips.map {
            it.label
        } shouldBe
            listOf(VolumeLabel.Number(1.0), VolumeLabel.Name("Finale"))
    }

    @Test
    fun `chips follow the order the user gave the volumes`() {
        val saved = mapOf("Series" to listOf(LocalVolume("Vol 02", 0, null), LocalVolume("Vol 01", 1, null)))

        listOf(row(3, v2), row(1, v1)).inVolumes(picked = null, saved, every = emptyList())!!.chips.map {
            it.key
        } shouldBe
            listOf("Vol 02", "Vol 01")
    }

    /** Reorder, Delete and Rename files act on the chips, so a filtered-out volume must stay among them. */
    @Test
    fun `a volume the chapter filter hides keeps its chip`() {
        val v3 = mangaChapterVolume(LOCAL, "Series/Vol 03/Ch 005.cbz", "Vol 03 - Ch 005")!!

        listOf(row(1, v1), row(3, v2)).inVolumes(picked = null, saved = emptyMap(), every = listOf(v1, v2, v3))!!
            .chips.map { it.key } shouldBe listOf("Vol 01", "Vol 02", "Vol 03")
    }

    // Two books of one series that both state volume 1, as after combining two series.
    private val book1 = novelChapterVolume("Series", "Series::Book%20One.epub::c1.xhtml::t", "Vol. 1 - Prologue")!!
    private val other1 = novelChapterVolume("Series", "Series::Other.epub::c1.xhtml::t", "Vol. 1 - Prologue")!!

    @Test
    fun `a later volume showing an earlier one's label is numbered after it`() {
        listOf(row(1, book1), row(2, other1)).inVolumes(picked = null, saved = emptyMap(), every = emptyList())!!.chips
            .map { it.duplicate } shouldBe listOf(0, 1)
    }

    @Test
    fun `volumes that show one label are asked about together`() {
        listOf(
            row(1, book1),
            row(2, other1),
            row(3, v2),
        ).inVolumes(picked = null, saved = emptyMap(), every = emptyList())!!
            .duplicates.map { same -> same.map { it.key } } shouldBe listOf(listOf("Book One.epub", "Other.epub"))
    }

    @Test
    fun `a volume kept under its own name no longer clashes`() {
        val saved = mapOf("Series" to listOf(LocalVolume("Other.epub", position = null, label = "Vol. 1 (2)")))

        listOf(row(1, book1), row(2, other1)).inVolumes(picked = null, saved, every = emptyList())!!.duplicates shouldBe
            emptyList()
    }
}
