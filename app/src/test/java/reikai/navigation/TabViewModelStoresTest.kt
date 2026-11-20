package reikai.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import mihon.core.navigation.Navigator
import mihon.core.navigation.NavigatorDisplay
import mihon.core.navigation.rememberNavigator
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Each tab keeps its own models across switches and pages pushed over Home, and loses them with Home or when
 * it leaves the bar.
 */
@RunWith(RobolectricTestRunner::class)
class TabViewModelStoresTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var outer: Navigator
    private lateinit var tabs: Navigator
    private val seen = mutableMapOf<NavKey, Probe>()
    private var dropped by mutableStateOf(emptyList<NavKey>())

    @Test
    fun `two tabs resolving one model type get one instance each`() {
        show()
        switchTo(TabB)
        val b = seen.getValue(TabB)
        switchTo(TabC)

        (seen.getValue(TabC) === b) shouldBe false
    }

    @Test
    fun `a tab switched away and back keeps its model`() {
        show()
        switchTo(TabB)
        val b = seen.getValue(TabB)
        switchTo(TabC)
        switchTo(TabB)

        (seen.getValue(TabB) === b) shouldBe true
    }

    @Test
    fun `a page pushed over Home and popped leaves the tab's model as it was`() {
        show()
        switchTo(TabB)
        val b = seen.getValue(TabB)
        act { outer.push(Other) }
        act { outer.pop() }

        ((seen.getValue(TabB) === b) to b.cleared) shouldBe (true to false)
    }

    @Test
    fun `a tab that leaves the bar has its model cleared`() {
        show()
        switchTo(TabB)
        val b = seen.getValue(TabB)
        switchTo(TabC)
        act { dropped = listOf(TabB) }

        b.cleared shouldBe true
    }

    @Test
    fun `leaving Home clears every tab's model`() {
        show()
        switchTo(TabB)
        val models = listOf(seen.getValue(TabA), seen.getValue(TabB))
        act { outer.pop() }

        models.map { it.cleared } shouldBe listOf(true, true)
    }

    private fun show() {
        compose.setContent {
            outer = rememberNavigator(Start)
            NavigatorDisplay(
                navigator = outer,
                entryDecorators = listOf(
                    rememberSaveableStateHolderNavEntryDecorator(),
                    rememberViewModelStoreNavEntryDecorator(),
                ),
                entryProvider = entryProvider {
                    entry<Start> {}
                    entry<Other> {}
                    entry<Home> { HomeTabs() }
                },
            )
        }
        act { outer.push(Home) }
    }

    @Composable
    private fun HomeTabs() {
        tabs = rememberNavigator(TabA)
        NavigatorDisplay(
            navigator = tabs,
            entryDecorators = listOf(rememberTabViewModelStoresDecorator(dropped)),
            entryProvider = entryProvider {
                entry<TabA> { seen[TabA] = viewModel<Probe>() }
                entry<TabB> { seen[TabB] = viewModel<Probe>() }
                entry<TabC> { seen[TabC] = viewModel<Probe>() }
            },
        )
    }

    private fun switchTo(tab: NavKey) = act { tabs.replaceAll(tab) }

    private fun act(block: () -> Unit) {
        compose.runOnIdle(block)
        compose.waitForIdle()
    }

    class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    @Serializable
    data object Start : NavKey

    @Serializable
    data object Other : NavKey

    @Serializable
    data object Home : NavKey

    @Serializable
    data object TabA : NavKey

    @Serializable
    data object TabB : NavKey

    @Serializable
    data object TabC : NavKey
}
