package reikai.presentation.migrate.flow

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.result.ResultEventBus
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import eu.kanade.presentation.util.Screen
import io.kotest.matchers.shouldBe
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.entry.EntryId
import reikai.presentation.ComposeTestHost
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream

/**
 * A deep pick reaches the flow that asked for it through the flow id its screen saves, after process
 * death too, and never a flow with another id. Process death is the stack's screens through Java
 * serialization, as Android saves them, under a fresh bus, since the old one dies with the process.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationPickBusTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var navigator: Navigator
    private var generation by mutableStateOf(Generation(ResultEventBus(), listOf<Screen>(Root)))

    @Before
    fun host() {
        received.clear()
        compose.setContent {
            key(generation) {
                ComposeTestHost(resultEventBus = generation.bus) {
                    Navigator(generation.screens) {
                        navigator = it
                        DefaultNavigatorScreenTransition(it)
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a pick reaches the asking screen exactly once`() {
        act { navigator.push(Asking(FLOW)) }
        act { navigator.push(Picker(MigrationPickFor(ENTRY, FLOW))) }
        pickAndPop()

        received shouldBe listOf(PICK)
    }

    @Test
    fun `a pick made after process death reaches the restored asking screen`() {
        act { navigator.push(Asking(FLOW)) }
        act { navigator.push(Picker(MigrationPickFor(ENTRY, FLOW))) }
        processDeath()
        pickAndPop()

        received shouldBe listOf(PICK)
    }

    @Test
    fun `a pick for another flow is not received`() {
        act { navigator.push(Asking(FLOW)) }
        act { navigator.push(Picker(MigrationPickFor(ENTRY, FLOW + 1))) }
        pickAndPop()

        received shouldBe emptyList()
    }

    @Test
    fun `a pick left unread when the flow is left is dropped`() {
        act { navigator.push(Asking(FLOW)) }
        act { navigator.push(Picker(MigrationPickFor(ENTRY, FLOW))) }
        act { generation.bus.sendMigrationPick(MigrationPickFor(ENTRY, FLOW), PICK.targetRawId) }
        act { navigator.popUntilRoot() }
        act { navigator.push(Asking(FLOW)) }

        received shouldBe emptyList()
    }

    private fun pickAndPop() = act {
        val picker = navigator.lastItem as Picker
        generation.bus.sendMigrationPick(picker.migrateFor, PICK.targetRawId)
        navigator.pop()
    }

    private fun processDeath() {
        val saved = navigator.items.map { it.roundTrip() as Screen }
        generation = Generation(ResultEventBus(), saved)
        compose.waitForIdle()
    }

    private fun act(block: () -> Unit) {
        compose.runOnIdle(block)
        compose.waitForIdle()
    }

    private data class Generation(val bus: ResultEventBus, val screens: List<Screen>)

    private object Root : Screen() {
        private fun readResolve(): Any = Root

        @Composable
        override fun Content() = Unit
    }

    class PickModel : ViewModel()

    private class Asking(val flowId: Long) : Screen() {
        @Composable
        override fun Content() {
            MigrationPickEffect(flowId, viewModel { PickModel() }) { received += it }
        }
    }

    private class Picker(val migrateFor: MigrationPickFor) : Screen() {
        @Composable
        override fun Content() = Unit
    }

    private companion object {
        const val FLOW = 9137L
        val ENTRY = EntryId.Novel(3L)
        val PICK = MigrationPick(ENTRY, targetRawId = 55L)
        val received = mutableListOf<MigrationPick>()

        fun Any.roundTrip(): Any {
            val bytes = ByteArrayOutputStream().also { ObjectOutputStream(it).use { out -> out.writeObject(this) } }
            return ObjectInputStream(ByteArrayInputStream(bytes.toByteArray())).use { it.readObject() }
        }
    }
}
