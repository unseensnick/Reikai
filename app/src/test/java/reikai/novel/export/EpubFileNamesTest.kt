package reikai.novel.export

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldEndWith
import org.junit.jupiter.api.Test

/** What a folder export names each book, which an e-reader and the local source find by its extension. */
class EpubFileNamesTest {

    /** About 80 kanji, common for a light novel, runs past the name limit with the extension. */
    @Test
    fun `a title past the name limit keeps the extension`() {
        epubFileName("転生したら".repeat(16)) shouldEndWith ".epub"
    }

    @Test
    fun `a second novel of the same title gets a numbered name`() {
        val names = EpubFileNames()
        names.next("Overlord")

        names.next("Overlord") shouldBe "Overlord (2).epub"
    }

    /** A storage provider's folder matches names ignoring case, so these would replace each other too. */
    @Test
    fun `titles that differ only in case get different names`() {
        val names = EpubFileNames()
        names.next("Overlord")

        names.next("OVERLORD") shouldBe "OVERLORD (2).epub"
    }

    @Test
    fun `a numbered long title keeps its number and extension`() {
        val names = EpubFileNames()
        names.next("転生したら".repeat(16))

        names.next("転生したら".repeat(16)) shouldEndWith " (2).epub"
    }
}
