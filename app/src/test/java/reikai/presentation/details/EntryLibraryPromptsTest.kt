package reikai.presentation.details

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * The two library prompts a details page shows, for manga and novels alike: delete the downloads of
 * what just left the library, and add an entry to it after its first download.
 */
class EntryLibraryPromptsTest {

    private val context = mockk<Context>(relaxed = true)
    private val host = SnackbarHostState()
    private val offer = AddToLibraryOffer(host, context)
    private val deleted = mutableListOf<String>()
    private var added = 0
    private var inLibrary = false

    @Test
    fun `Delete removes the downloads of the removed entries that have some`() = runTest {
        offerDelete(removed = listOf("a", "b"), withDownloads = setOf("b"))

        host.currentSnackbarData!!.performAction()
        runCurrent()

        deleted shouldBe listOf("b")
    }

    @Test
    fun `dismissing the delete prompt deletes nothing`() = runTest {
        offerDelete(removed = listOf("a"), withDownloads = setOf("a"))

        host.currentSnackbarData!!.dismiss()
        runCurrent()

        deleted shouldBe emptyList()
    }

    @Test
    fun `nothing downloaded asks nothing`() = runTest {
        offerDelete(removed = listOf("a"), withDownloads = emptySet())

        host.currentSnackbarData.shouldBeNull()
    }

    @Test
    fun `Add adds an entry still outside the library`() = runTest {
        offerAdd()

        host.currentSnackbarData!!.performAction()
        runCurrent()

        added shouldBe 1
    }

    @Test
    fun `Add does nothing once the entry joined the library meanwhile`() = runTest {
        offerAdd()

        inLibrary = true
        host.currentSnackbarData!!.performAction()
        runCurrent()

        added shouldBe 0
    }

    @Test
    fun `dismissing the add prompt adds nothing`() = runTest {
        offerAdd()

        host.currentSnackbarData!!.dismiss()
        runCurrent()

        added shouldBe 0
    }

    @Test
    fun `a later download on the same screen asks nothing`() = runTest {
        offerAdd()
        host.currentSnackbarData!!.dismiss()
        runCurrent()

        offerAdd()

        host.currentSnackbarData.shouldBeNull()
    }

    @Test
    fun `an entry already in the library is never asked`() = runTest {
        inLibrary = true

        offerAdd()

        host.currentSnackbarData.shouldBeNull()
    }

    private fun TestScope.offerDelete(removed: List<String>, withDownloads: Set<String>) {
        backgroundScope.launch {
            host.offerToDeleteDownloads(context, removed, hasDownloads = { it in withDownloads }) { deleted += it }
        }
        runCurrent()
    }

    private fun TestScope.offerAdd() {
        backgroundScope.launch { offer.afterDownload(isInLibrary = { inLibrary }) { added++ } }
        runCurrent()
    }
}
