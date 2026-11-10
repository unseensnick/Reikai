package reikai.data.local

import android.net.Uri
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test

/** Which picked books are another version of one the series holds, which an import asks about. */
class NewerCopiesTest {

    private val picked = mockk<Uri>()

    @Test
    fun `a book with the identity of one the series holds is its newer copy`() {
        newerCopiesOf(
            listOf(ImportFile(picked, "Book.epub", 10, identity = bookIdentity("urn:uuid:1", null))),
            mapOf(bookIdentity("urn:uuid:1", null)!! to "Book - Vol. 1.epub"),
            emptySet(),
        ) shouldBe mapOf(picked to "Book - Vol. 1.epub")
    }

    @Test
    fun `two runs of one exported novel are different books`() {
        (bookIdentity("urn:reikai:novel:s:u", 1.0) == bookIdentity("urn:reikai:novel:s:u", 51.0)) shouldBe false
    }
}
