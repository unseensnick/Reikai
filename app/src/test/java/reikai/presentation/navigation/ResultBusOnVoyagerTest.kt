package reikai.presentation.navigation

import android.app.Application
import android.content.pm.ActivityInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.navigation3.runtime.result.LocalResultEventBus
import androidx.navigation3.runtime.result.ResultEffect
import androidx.navigation3.runtime.result.ResultEventBus
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import eu.kanade.presentation.util.DefaultNavigatorScreenTransition
import io.kotest.matchers.shouldBe
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The result bus as MainActivity hosts it under Voyager: a screen's collector runs only while that
 * screen is on top, as it will under Navigation 3's display, and the app's bus outlives a recreate.
 */
@RunWith(RobolectricTestRunner::class)
class ResultBusOnVoyagerTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<BusHostActivity>

    @After
    fun close() = scenario.close()

    @Test
    fun `a result sent before the sender pops reaches the screen below exactly once`() {
        launch()
        act { navigator.push(Sender) }
        act {
            bus.sendResult(Picked(7))
            navigator.pop()
        }

        received shouldBe listOf(Picked(7))
    }

    @Test
    fun `a covered screen collects nothing until it is back on top`() {
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

    data object Receiver : Screen {
        @Composable
        override fun Content() {
            ResultEffect<Picked> { received += it }
        }
    }

    data object Sender : Screen {
        @Composable
        override fun Content() = Unit
    }

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
            ResultBusOnVoyagerTest.bus = bus
            CompositionLocalProvider(LocalResultEventBus provides bus) {
                Navigator(ResultBusOnVoyagerTest.Receiver) {
                    ResultBusOnVoyagerTest.navigator = it
                    DefaultNavigatorScreenTransition(it)
                }
            }
        }
    }
}
