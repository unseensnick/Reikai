package reikai.presentation.local

import android.net.Uri
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import org.junit.jupiter.api.Test
import reikai.presentation.OtherTestRoute
import reikai.presentation.RecordingResultBus
import reikai.presentation.TestRootRoute
import reikai.presentation.resultKey
import reikai.presentation.testNavigator

/** Files opened with the app join an open import screen's picks, else open one of their own. */
class ImportFilesTest {

    private val recorder = RecordingResultBus()
    private val files = listOf(mockk<Uri>())

    @Test
    fun `files join the import screen already open, which is shown again`() {
        val open = LocalImportRoute()
        val stack = testNavigator(TestRootRoute, open, OtherTestRoute)

        stack.importFiles(files, recorder.bus)

        (stack.current to recorder.sent) shouldBe
            (open to listOf(resultKey<ImportFilesEvent>() to ImportFilesEvent(files)))
    }

    @Test
    fun `with no import screen open the files open a new one over the library`() {
        val stack = testNavigator(TestRootRoute, OtherTestRoute)

        stack.importFiles(files, recorder.bus)

        stack.routes shouldBe listOf(TestRootRoute, LocalImportRoute(files.map { it.toString() }))
    }

    @Test
    fun `a new import screen is not also sent the files`() {
        val stack = testNavigator(TestRootRoute)

        stack.importFiles(files, recorder.bus)

        recorder.sent shouldBe emptyList()
    }
}
