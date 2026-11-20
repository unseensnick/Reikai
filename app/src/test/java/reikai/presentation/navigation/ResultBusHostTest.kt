package reikai.presentation.navigation

import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import androidx.navigation3.runtime.result.ResultEventBus
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.kotest.matchers.shouldBe
import kotlinx.serialization.Serializable
import mihon.core.navigation.Navigator
import mihon.core.navigation.rememberNavigator
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import reikai.presentation.TestNavHost

/**
 * The result bus as MainActivity hosts it: a route's collector runs only while that route is shown, and the
 * app's bus outlives a recreate, so a result sent just before one still arrives.
 */
@RunWith(RobolectricTestRunner::class)
class ResultBusHostTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<BusHostActivity>

    @After
    fun close() = scenario.close()

    @Test
    fun `a result sent before the sender pops reaches the route below exactly once`() {
        launch()
        act { navigator.push(Sender) }
        act {
            bus.sendResult(Picked(7))
            navigator.pop()
        }

        received shouldBe listOf(Picked(7))
    }

    @Test
    fun `a covered route collects nothing until it is back on top`() {
        launch()
        act { navigator.push(Sender) }
        act { bus.sendResult(Picked(7)) }

        received shouldBe emptyList()
    }

    @Test
    fun `a result sent before a recreate arrives once after it, on the same bus`() {
        launch()
        act { navigator.push(Sender) }
        val before = bus
        act { bus.sendResult(Picked(7)) }
        scenario.recreate()
        compose.waitForIdle()
        act { navigator.pop() }

        ((bus === before) to received.toList()) shouldBe (true to listOf(Picked(7)))
    }

    private fun launch() {
        received.clear()
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addOrUpdateActivity(
            ActivityInfo().apply {
                name = BusHostActivity::class.java.name
                packageName = app.packageName
            },
        )
        scenario = ActivityScenario.launch(BusHostActivity::class.java)
        compose.waitForIdle()
    }

    private fun act(block: () -> Unit) {
        scenario.onActivity { block() }
        compose.waitForIdle()
    }

    data class Picked(val id: Long)

    @Serializable
    data object Receiver : NavKey

    @Serializable
    data object Sender : NavKey

    companion object {
        val received = mutableListOf<Picked>()
        lateinit var navigator: Navigator
        lateinit var bus: ResultEventBus
    }
}

/** Hosts the bus as MainActivity does, its content set again in each onCreate as an app's is. */
class BusHostActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val bus = retainAppResultEventBus()
            ResultBusHostTest.bus = bus
            val navigator = rememberNavigator(ResultBusHostTest.Receiver)
            ResultBusHostTest.navigator = navigator
            CompositionLocalProvider(LocalResultEventBus provides bus) {
                TestNavHost(navigator) {
                    entry<ResultBusHostTest.Receiver> {
                        ResultEffect<ResultBusHostTest.Picked> { ResultBusHostTest.received += it }
                    }
                    entry<ResultBusHostTest.Sender> {}
                }
            }
        }
    }
}
