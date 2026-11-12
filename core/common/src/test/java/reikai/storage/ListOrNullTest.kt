package reikai.storage

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import com.hippo.unifile.UniFile
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/** A folder's listing is trusted only when it is all of the folder. */
class ListOrNullTest {

    private val folderUri = mockk<Uri>()
    private val childrenUri = mockk<Uri>()
    private val files = arrayOf(mockk<UniFile>(), mockk<UniFile>())
    private val folder = mockk<UniFile> {
        every { uri } returns folderUri
        every { listFiles() } returns files
    }

    @BeforeEach
    fun setUp() {
        mockkStatic(DocumentsContract::class)
        every { DocumentsContract.isTreeUri(folderUri) } returns true
        every { DocumentsContract.getDocumentId(folderUri) } returns "primary:Series"
        every { DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, "primary:Series") } returns childrenUri
    }

    @AfterEach
    fun tearDown() = unmockkStatic(DocumentsContract::class)

    private fun counting(cursor: Cursor?) = mockk<Context> {
        every { contentResolver.query(childrenUri, any(), null, null, null) } returns cursor
    }

    private fun cursor(count: Int, loading: Boolean = false) = mockk<Cursor>(relaxed = true) {
        every { getCount() } returns count
        every { extras } returns mockk<Bundle> {
            every { getBoolean(DocumentsContract.EXTRA_LOADING) } returns loading
        }
    }

    @Test
    fun `a listing the folder's count agrees with is its files`() {
        folder.listOrNull(counting(cursor(2))) shouldBe files.toList()
    }

    @Test
    fun `a listing shorter than the folder is not trusted`() {
        folder.listOrNull(counting(cursor(3))) shouldBe null
    }

    @Test
    fun `a folder the provider cannot count is not trusted`() {
        folder.listOrNull(counting(null)) shouldBe null
    }

    @Test
    fun `a count that fails is not trusted`() {
        val context = mockk<Context> {
            every { contentResolver.query(childrenUri, any(), null, null, null) } throws SecurityException()
        }
        folder.listOrNull(context) shouldBe null
    }

    @Test
    fun `a listing still loading is not trusted`() {
        folder.listOrNull(counting(cursor(2, loading = true))) shouldBe null
    }

    @Test
    fun `a plain folder's listing is trusted as it is`() {
        every { DocumentsContract.isTreeUri(folderUri) } returns false
        folder.listOrNull(mockk()) shouldBe files.toList()
    }
}
