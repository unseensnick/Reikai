package reikai.presentation.local

import android.net.Uri
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.core.stack.mutableStateStackOf
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.presentation.RecordingResultBus
import reikai.presentation.resultKey

/** Files opened with the app join an open import screen's picks, else open one of their own. */
class ImportFilesTest {

    private val recorder = RecordingResultBus()
    private val files = listOf(mockk<Uri>())
    private val root = mockk<Screen>()

    @Test
    fun `files join the import screen already open, which is shown again`() {
        val open = LocalImportRoute()
        val stack = mutableStateStackOf(root, open, mockk<Screen>(), minSize = 1)

        stack.importFiles(files, recorder.bus)

        (stack.lastItemOrNull to recorder.sent) shouldBe
            (open to listOf(resultKey<ImportFilesEvent>() to ImportFilesEvent(files)))
    }

    @Test
    fun `with no import screen open the files open a new one over the library`() {
        val stack = mutableStateStackOf(root, mockk<Screen>(), minSize = 1)

        stack.importFiles(files, recorder.bus)

        stack.items.map { it::class } shouldBe listOf(root::class, LocalImportRoute::class)
    }

    @Test
    fun `a new import screen is not also sent the files`() {
        val stack = mutableStateStackOf(root, minSize = 1)

        stack.importFiles(files, recorder.bus)

        recorder.sent shouldBe emptyList()
    }
}
