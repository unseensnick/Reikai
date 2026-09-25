package reikai.domain.library

import eu.kanade.tachiyomi.data.export.LibraryExporter
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.novel.model.Novel
import tachiyomi.domain.manga.model.Manga

class LibraryExportRowsTest {

    private val allColumns = LibraryExporter.ExportOptions(
        includeTitle = true,
        includeAuthor = true,
        includeArtist = true,
    )

    private fun manga(id: Long, title: String) = Manga.create().copy(id = id, title = title, author = "A")

    private fun novel(id: Long, title: String) = Novel.create().copy(id = id, title = title, author = "B")

    @Test
    fun `the library list carries a novel beside a manga`() {
        val rows = libraryExportRows(
            manga = listOf(manga(1, "Manga")),
            novels = listOf(novel(1, "Novel")),
            mangaGroups = emptyMap(),
            novelGroups = emptyMap(),
        )

        LibraryExporter.generateCsvData(rows, allColumns) shouldBe "Manga,A,\r\nNovel,B,"
    }

    @Test
    fun `a merged series is written once, as its lowest id member`() {
        val rows = libraryExportRows(
            manga = listOf(manga(3, "Second source"), manga(2, "First source")),
            novels = listOf(novel(5, "Novel one"), novel(4, "Novel two")),
            mangaGroups = mapOf(2L to 10L, 3L to 10L),
            novelGroups = mapOf(4L to 20L, 5L to 20L),
        )

        rows.map { it.title } shouldBe listOf("First source", "Novel two")
    }
}
