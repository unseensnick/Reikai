package reikai.presentation.local

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import reikai.data.local.ImportDocuments
import reikai.data.local.ImportDocuments.Document
import reikai.data.local.describe

/** Which files an Open with or Share hands the import, and how each is named. */
@RunWith(RobolectricTestRunner::class)
class ImportIntentsTest {

    private val resolver = ApplicationProvider.getApplicationContext<android.app.Application>().contentResolver

    @Before
    fun setUp() {
        Robolectric.setupContentProvider(ImportDocuments::class.java, ImportDocuments.AUTHORITY)
    }

    @After
    fun tearDown() {
        ImportDocuments.documents.clear()
    }

    @Test
    fun `a wildcard share of document ids keeps the files its provider names as books`() {
        ImportDocuments.documents["1"] = Document("a.cbz", 1L, "application/octet-stream")
        ImportDocuments.documents["2"] = Document("b.epub", 1L, null)
        ImportDocuments.documents["3"] = Document("notes.txt", 1L, "text/plain")
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(listOf("1", "2", "3").map(ImportDocuments::uri)))
        }

        importedFiles(intent, resolver) shouldBe listOf(ImportDocuments.uri("1"), ImportDocuments.uri("2"))
    }

    @Test
    fun `a file path is never taken, whatever type the sender gives it`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/x-cbz"
            putExtra(Intent.EXTRA_STREAM, Uri.parse("file:///data/data/other.app/files/a.cbz"))
        }

        importedFiles(intent, resolver) shouldBe emptyList()
    }

    @Test
    fun `a provider's name cannot step out of the series folder`() {
        ImportDocuments.documents["1"] = Document("../../x.cbz", 1L, null)

        resolver.describe(ImportDocuments.uri("1")).name.contains('/') shouldBe false
    }

    @Test
    fun `a provider that gives no size leaves it unknown rather than empty`() {
        ImportDocuments.documents["1"] = Document("a.cbz", null, null)

        resolver.describe(ImportDocuments.uri("1")).size shouldBe -1L
    }
}
