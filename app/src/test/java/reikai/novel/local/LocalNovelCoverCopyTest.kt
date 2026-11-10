package reikai.novel.local

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LocalNovelCoverCopyTest {

    @TempDir
    lateinit var dir: File

    private fun copyName(book: String, modified: Long) = LocalNovelSource.coverCopyName("S", book, modified) + ".jpg"

    @Test
    fun `a book's new copy replaces its earlier ones and leaves other books' alone`() {
        listOf(copyName("a.epub", 1), copyName("a.epub", 1) + "123.part", copyName("b.epub", 1))
            .forEach { File(dir, it).writeBytes(byteArrayOf(1)) }

        LocalNovelSource.storeCoverCopy(dir, "S", "a.epub", copyName("a.epub", 2)) { byteArrayOf(2) }

        dir.list()!!.sorted() shouldBe listOf(copyName("a.epub", 2), copyName("b.epub", 1)).sorted()
    }

    @Test
    fun `a copy already made is served without reading the book again`() {
        File(dir, copyName("a.epub", 1)).writeBytes(byteArrayOf(1))

        LocalNovelSource.storeCoverCopy(dir, "S", "a.epub", copyName("a.epub", 1)) { error("read again") }!!
            .readBytes().toList() shouldBe listOf<Byte>(1)
    }
}
