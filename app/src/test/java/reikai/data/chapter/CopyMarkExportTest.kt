package reikai.data.chapter

import android.content.Context
import android.net.Uri
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import reikai.domain.chapter.InMemoryChapterCopyMarks
import java.io.IOException

/** The export runs off a picked file the provider may refuse, which must report rather than crash. */
class CopyMarkExportTest {

    private fun export(context: Context) = runTest {
        writeCopyMarkReport(context, mockk<Uri>(), InMemoryChapterCopyMarks(), mockk(), mockk()) shouldBe null
    }

    @Test
    fun `a file the provider will not open reports a failure`() = export(
        mockk { every { contentResolver.openOutputStream(any(), any()) } returns null },
    )

    @Test
    fun `a file that fails while written reports a failure`() = export(
        mockk { every { contentResolver.openOutputStream(any(), any()) } throws IOException("gone") },
    )
}
