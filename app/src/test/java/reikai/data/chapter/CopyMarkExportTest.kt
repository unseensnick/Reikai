package reikai.data.chapter

import android.content.Context
import android.net.Uri
import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import reikai.domain.chapter.ChapterCopyMarkRepository
import reikai.domain.chapter.CopyMark
import reikai.domain.chapter.CopyMarkReport
import reikai.domain.chapter.InMemoryChapterCopyMarks
import reikai.domain.library.ContentType
import reikai.novel.source.NovelSourceManager
import java.io.ByteArrayOutputStream
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

    // By the name a backup or a library search reads, which an uninstalled plugin still has, without loading any.
    @Test
    fun `a novel's source is named as the rest of the app names it, with no plugin loaded`() = runTest {
        val written = ByteArrayOutputStream()
        val context = mockk<Context> { every { contentResolver.openOutputStream(any(), any()) } returns written }
        val marks = object : ChapterCopyMarkRepository by InMemoryChapterCopyMarks() {
            override suspend fun report(type: ContentType) = if (type == ContentType.NOVELS) {
                listOf(CopyMarkReport("Novel", "plugin", "/n", "1", 1.0, null, CopyMark.NeverSkip))
            } else {
                emptyList()
            }
        }
        // Strict: asking for the source itself would load the plugins and fail here.
        val novelSources = mockk<NovelSourceManager>()
        coEvery { novelSources.nameOf("plugin") } returns "Seen name"

        writeCopyMarkReport(context, mockk(), marks, mockk(), novelSources)

        Json.parseToJsonElement(written.toString()).jsonObject["novels"]!!.jsonArray.single().jsonObject["source"]!!
            .jsonPrimitive.content shouldBe "Seen name"
    }
}
