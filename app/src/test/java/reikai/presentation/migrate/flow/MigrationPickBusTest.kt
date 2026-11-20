package reikai.presentation.migrate.flow

import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.ResultEventBus
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import mihon.core.navigation.Navigator
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import reikai.domain.entry.EntryId
import reikai.domain.source.SourceKey
import reikai.presentation.ComposeTestHost
import reikai.presentation.OtherTestRoute
import reikai.presentation.TestNavHost
import reikai.presentation.TestRootRoute
import reikai.presentation.browse.catalogue.EntryCatalogueRoute
import reikai.presentation.roundTrip
import reikai.presentation.testNavigator

/**
 * A deep pick reaches the flow that asked for it through the flow id its screen saves, after process
 * death too, and never a flow with another id. Process death is the stack's routes through the saved-state
 * path a back stack takes, under a fresh bus, since the old one dies with the process.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationPickBusTest {

    @get:Rule
    val compose = createComposeRule()

    private var generation by mutableStateOf(Generation(ResultEventBus(), testNavigator(Root)))
    private val navigator get() = generation.navigator

    @Before
    fun host() {
        received.clear()
        compose.setContent {
            key(generation) {
                ComposeTestHost(resultEventBus = generation.bus) {
                    TestNavHost(generation.navigator) {
                        entry<Root> {}
                        entry<Asking> { route ->
                            MigrationPickEffect(route.flowId, viewModel { PickModel() }, route.ready) { received += it }
                        }
                        entry<EntryCatalogueRoute> { route -> report = rememberMigrationPickReport(route.migrateFor) }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun `a pick reaches the asking screen exactly once`() {
        act { navigator.push(Asking(FLOW)) }
        act { navigator.push(picker(MigrationPickFor(ENTRY, FLOW))) }
        pickAndPop()

        received shouldBe listOf(PICK)
    }

    // A second tap, or a novel pick landing as the catalogue leaves, must not pop the asking screen past its guard.
    @Test
    fun `a second pick from a catalogue already leaving neither sends nor pops`() {
        act { navigator.push(Asking(FLOW)) }
        act { navigator.push(picker(MigrationPickFor(ENTRY, FLOW))) }
        act {
            report(PICK.target)
            report(PICK.target + 1)
        }

        (navigator.routes.map { it::class } to received) shouldBe (listOf(Root::class, Asking::class) to listOf(PICK))
    }

    @Test
    fun `a pick made after process death reaches the restored asking screen`() {
        act { navigator.push(Asking(FLOW)) }
        act { navigator.push(picker(MigrationPickFor(ENTRY, FLOW))) }
        processDeath()
        pickAndPop()

        received shouldBe listOf(PICK)
    }

    @Test
    fun `a pick for another flow is not received`() {
        act { navigator.push(Asking(FLOW)) }
        act { navigator.push(picker(MigrationPickFor(ENTRY, FLOW + 1))) }
        pickAndPop()

        received shouldBe emptyList()
    }

    @Test
    fun `a pick left unread when the flow is left is dropped`() {
        act { navigator.push(Asking(FLOW)) }
        act { navigator.push(picker(MigrationPickFor(ENTRY, FLOW))) }
        act { generation.bus.sendMigrationPick(MigrationPickFor(ENTRY, FLOW), PICK.target) }
        act { navigator.popToRoot() }
        act { navigator.push(Asking(FLOW)) }

        received shouldBe emptyList()
    }

    // The cleanup is registered while the screen still loads, so a flow left before its rows came back drops it too.
    @Test
    fun `a pick left unread by a flow left while loading is dropped`() {
        act { navigator.push(Asking(FLOW, ready = false)) }
        act { generation.bus.sendMigrationPick(MigrationPickFor(ENTRY, FLOW), PICK.target) }
        act { navigator.popToRoot() }
        act { navigator.push(Asking(FLOW)) }

        received shouldBe emptyList()
    }

    private fun pickAndPop() = act {
        val picker = navigator.current as EntryCatalogueRoute
        generation.bus.sendMigrationPick(picker.migrateFor!!, PICK.target)
        navigator.pop()
    }

    private fun processDeath() {
        val saved = navigator.routes.map(::roundTrip)
        generation = Generation(ResultEventBus(), testNavigator(*saved.toTypedArray()))
        compose.waitForIdle()
    }

    private fun act(block: () -> Unit) {
        compose.runOnIdle(block)
        compose.waitForIdle()
    }

    private data class Generation(val bus: ResultEventBus, val navigator: Navigator)

    @Serializable
    data object Root : NavKey

    class PickModel : ViewModel()

    @Serializable
    data class Asking(val flowId: Long, val ready: Boolean = true) : NavKey, MigrationFlowRoute

    private companion object {
        const val FLOW = 9137L
        val ENTRY = EntryId.Novel(3L)
        val PICK = MatchOverrideEvent(ENTRY, target = 55L)
        val received = mutableListOf<MatchOverrideEvent>()
        var report: (Long) -> Unit = {}
    }
}

private fun picker(migrateFor: MigrationPickFor) = EntryCatalogueRoute(
    SourceKey.Novel("plugin"),
    migrateFor = migrateFor,
)

/** A pick is kept when the catalogue was first drawn under another page, as a back gesture over it draws it. */
@RunWith(RobolectricTestRunner::class)
class MigrationPickReportTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a pick reaches the flow when the catalogue was first drawn under another page`() {
        val request = MigrationPickFor(EntryId.Novel(3L), flowId = 1L)
        val stack = testNavigator(TestRootRoute, Flow, picker(request), OtherTestRoute)
        var report: (Long) -> Unit = {}
        compose.setContent { ComposeTestHost(navigator = stack) { report = rememberMigrationPickReport(request) } }

        compose.runOnIdle { stack.pop() }
        compose.runOnIdle { report(55L) }

        stack.routes shouldBe listOf(TestRootRoute, Flow)
    }

    @Serializable
    data object Flow : NavKey, MigrationFlowRoute
}
