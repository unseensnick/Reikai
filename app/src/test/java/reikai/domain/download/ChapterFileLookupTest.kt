package reikai.domain.download

import android.net.Uri
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * A listed folder must find what `findFile` finds, or a delete misses a file the reader still opens. A
 * storage access framework folder matches names ignoring case.
 */
class ChapterFileLookupTest {

    private val file = mockk<UniFile> { every { name } returns "Chapter 1.html" }

    private fun folder(uriScheme: String) = mockk<UniFile> {
        every { uri } returns mockk<Uri> { every { scheme } returns uriScheme }
        every { listFiles() } returns arrayOf(file)
        every { findFile(any()) } returns null
    }

    @Test
    fun `a listed document folder matches a name that differs only in case`() {
        folder("content").chapterFileLookup(lookups = 50)("chapter 1.html") shouldBe file
    }

    @Test
    fun `a plain folder is asked by name however many lookups`() {
        folder("file").chapterFileLookup(lookups = 50)("Chapter 1.html") shouldBe null
    }
}
