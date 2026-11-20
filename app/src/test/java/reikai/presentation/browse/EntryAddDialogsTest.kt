package reikai.presentation.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import mihon.core.navigation.Navigator
import mihon.core.navigation.rememberNavigator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import reikai.presentation.ComposeTestHost
import reikai.presentation.TestRootRoute

/**
 * The long-press dialogs drawn over each content type's real add flow, every button and card gesture
 * included. The flow's own conformance test calls its verbs directly, so only this one sees a button
 * wired to the wrong verb.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class EntryAddDialogsTest(private val newProbe: ProbeFactory) {

    @get:Rule
    val compose = createComposeRule()

    // The runner hands every test the same parameters, so each builds its own library.
    private val probe = newProbe.create()

    private lateinit var navigator: Navigator

    private fun showDialogs() = compose.setContent {
        navigator = rememberNavigator(TestRootRoute)
        ComposeTestHost(navigator = navigator) { DialogHost(probe.flow).Content() }
    }

    @Test
    fun `confirming a removal takes the entry out of the library`() = runTest {
        probe.storeEntry(inLibrary = true)
        probe.pressEntry()
        showDialogs()

        compose.onNodeWithText("Remove").performClick()
        probe.act {}

        probe.entryInLibrary() shouldBe false
    }

    @Test
    fun `a ticked category is where the entry is filed`() = runTest {
        probe.storeEntry(inLibrary = false)
        probe.pressEntry()
        showDialogs()

        compose.onNodeWithText("category ${EntryAddFlowConformanceTest.PICKED_CATEGORY}").performClick()
        compose.onNodeWithText("OK").performClick()
        probe.act {}

        probe.entryCategories() shouldBe listOf(EntryAddFlowConformanceTest.PICKED_CATEGORY)
    }

    @Test
    fun `adding a duplicate anyway asks for categories`() = runTest {
        probe.storeDuplicate()
        probe.storeEntry(inLibrary = false)
        probe.pressEntry()
        showDialogs()

        compose.onNodeWithText("Add anyway").performScrollTo().performClick()
        probe.act {}

        compose.onNodeWithText("Set categories").assertIsDisplayed()
    }

    @Test
    fun `adding to a duplicate's group puts the entry in the library`() = runTest {
        probe.storeDuplicate()
        probe.storeEntry(inLibrary = false)
        probe.pressEntry()
        showDialogs()

        compose.onNodeWithContentDescription("Select").performClick()
        compose.onNodeWithText(EntryAddFlowConformanceTest.DUPLICATE_TITLE).performClick()
        compose.onNodeWithText("Add to existing group").performScrollTo().performClick()
        probe.act {}

        probe.entryInLibrary() shouldBe true
    }

    @Test
    fun `tapping a duplicate card offers to migrate it onto the entry`() = runTest {
        val duplicate = probe.storeDuplicate()
        probe.storeEntry(inLibrary = false)
        probe.pressEntry()
        showDialogs()

        compose.onNodeWithText(EntryAddFlowConformanceTest.DUPLICATE_TITLE).performClick()
        probe.act {}

        probe.flow.dialog.value shouldBe EntryAddDialog.Migrate(currentId = duplicate, targetId = probe.entryId())
    }

    @Test
    fun `long-pressing a duplicate card opens that duplicate`() = runTest {
        val duplicate = probe.storeDuplicate()
        probe.storeEntry(inLibrary = false)
        probe.pressEntry()
        showDialogs()

        compose.onNodeWithText(EntryAddFlowConformanceTest.DUPLICATE_TITLE).performTouchInput { longClick() }

        compose.runOnIdle { navigator.current shouldBe probe.flow.duplicateScreen(duplicate) }
    }

    class ProbeFactory(private val name: String, val create: () -> AddFlowProbe) {
        override fun toString() = name
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun probes(): List<Array<Any>> = listOf(
            arrayOf(ProbeFactory("manga", ::MangaAddFlowProbe)),
            arrayOf(ProbeFactory("novel", ::NovelAddFlowProbe)),
        )
    }
}

private class DialogHost(private val flow: EntryAddFlow<*>) {
    // The migrate question opens the migration host, which needs the app graph; the cases stop at the flow.
    @Composable
    fun Content() {
        val dialog by flow.dialog.collectAsState()
        if (dialog !is EntryAddDialog.Migrate) EntryAddDialogs(flow)
    }
}
