package reikai.presentation.browse

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import reikai.presentation.ComposeTestHost

/**
 * The long-press dialogs drawn over each content type's real add flow. The flow's own conformance
 * test calls its verbs directly, so only this one sees a button wired to the wrong verb.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class EntryAddDialogsTest(private val probe: AddFlowProbe) {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `confirming a removal takes the entry out of the library`() = runTest {
        probe.storeEntry(inLibrary = true)
        probe.pressEntry()
        compose.setContent { ComposeTestHost { Navigator(DialogHost(probe.flow)) } }

        compose.onNodeWithText("Remove").performClick()
        probe.act {}

        probe.entryInLibrary() shouldBe false
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun probes(): List<Array<Any>> = listOf(arrayOf(MangaAddFlowProbe()), arrayOf(NovelAddFlowProbe()))
    }
}

private class DialogHost(private val flow: EntryAddFlow<*>) : Screen {
    @Composable
    override fun Content() = EntryAddDialogs(flow)
}
