package reikai.data.local

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.library.ContentType

class LocalFileNamesTest {

    @Test
    fun `a manga folder takes the title and each volume folder its label`() {
        localFileNames(
            ContentType.MANGA,
            "old",
            "Volume Comic",
            listOf("Vol 01" to "Vol. 1", "Vol 02" to "Finale"),
        ) shouldBe
            LocalFileNames(
                LocalFileNames.Rename("old", "Volume Comic"),
                listOf(LocalFileNames.Rename("Vol 01", "Vol. 1"), LocalFileNames.Rename("Vol 02", "Finale")),
            )
    }

    @Test
    fun `a book file takes the title and its label`() {
        localFileNames(ContentType.NOVELS, "Volume Test", "Spike", listOf("a.epub" to "Vol. 2")).volumes shouldBe
            listOf(LocalFileNames.Rename("a.epub", "Spike - Vol. 2.epub"))
    }

    @Test
    fun `a loose book is the series and takes the title`() {
        localFileNames(ContentType.NOVELS, "war3.epub", "War and Peace", emptyList()) shouldBe
            LocalFileNames(LocalFileNames.Rename("war3.epub", "War and Peace.epub"), emptyList())
    }

    @Test
    fun `two volumes with one label get distinct names`() {
        localFileNames(ContentType.MANGA, "S", "S", listOf("a" to "Extra", "b" to "Extra")).volumes.map {
            it.to
        } shouldBe
            listOf("Extra", "Extra (2)")
    }

    @Test
    fun `names that already match change nothing`() {
        localFileNames(ContentType.MANGA, "S", "S", listOf("Vol. 1" to "Vol. 1")).changes shouldBe false
    }
}
