package reikai.presentation.migrate.flow

import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.presentation.ComposeTestHost

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
        pickHandoff = MigrationPickHandoff(),
        io = Dispatchers.Unconfined,
    )

    @Before
    fun showList() {
        compose.setContent { ComposeTestHost { Navigator(listOf(Below, ListHost(model))) } }
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
    }

    private object Below : Screen {
        @Composable
        override fun Content() = Text(BELOW)
    }

    private class ListHost(private val model: EntryMigrationListViewModel) : Screen, MigrationFlowScreen {
        @Composable
        override fun Content() {
            val state by model.state.collectAsState()
            EntryMigrationList(state = state, viewModel = model)
        }
    }
}
