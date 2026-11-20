package reikai.presentation.migrate.flow

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.navigation3.runtime.NavKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.serialization.Serializable
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost
import reikai.presentation.TestNavHost
import reikai.presentation.testNavigator

/**
 * The migration list over a real model, with a screen below it to leave to: every way out while rows
 * are in flight has to ask first, and Stop has to actually leave.
 */
@RunWith(RobolectricTestRunner::class)
class EntryMigrationListTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val entries = listOf(migrationEntry(1), migrationEntry(2))
    private val model = EntryMigrationListViewModel(
        entryIds = entries.map { it.id.rawId },
        adapter = FakeMigrationFlowAdapter(entries),
        io = Dispatchers.Unconfined,
    )

    @Before
    fun showList() {
        compose.setContent {
            ComposeTestHost {
                TestNavHost(remember { testNavigator(Below, EarlierStep, ListHost) }) {
                    entry<Below> { Text(BELOW) }
                    entry<EarlierStep> { Text(EARLIER_STEP) }
                    entry<ListHost> {
                        val state by model.state.collectAsState()
                        EntryMigrationList(state = state, viewModel = model, flowId = 1L)
                    }
                }
            }
        }
        compose.waitUntil { model.state.value.let { !it.isLoading && it.rows.isNotEmpty() } }
    }

    // A model built outside a ViewModelStore is never cleared, so its work would outlive the test.
    @After
    fun clearModel() = model.viewModelScope.cancel()

    @Test
    fun `back asks before stopping`() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.onNodeWithText("Stop migrating?").assertIsDisplayed()
    }

    @Test
    fun `the up arrow asks before stopping`() {
        compose.onNodeWithContentDescription("Navigate up").performClick()

        compose.onNodeWithText("Stop migrating?").assertIsDisplayed()
    }

    @Test
    fun `stopping leaves the flow`() {
        compose.onNodeWithContentDescription("Navigate up").performClick()

        compose.onNodeWithText("Stop").performClick()

        compose.onNodeWithText(BELOW).assertIsDisplayed()
    }

    private companion object {
        const val BELOW = "the screen the flow started from"
        const val EARLIER_STEP = "an earlier step of the flow"
    }

    @Serializable
    data object Below : NavKey

    /** A step before the list, so a single pop would land inside the flow rather than leave it. */
    @Serializable
    data object EarlierStep : NavKey, MigrationFlowRoute

    /** The list itself, inside the flow, over the test's own model. */
    @Serializable
    data object ListHost : NavKey, MigrationFlowRoute
}
