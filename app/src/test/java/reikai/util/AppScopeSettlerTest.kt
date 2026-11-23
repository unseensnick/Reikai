package reikai.util

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/** Work a test leaves on its app scope ends with it, so it cannot reach a reset Main and fail the next test. */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AppScopeSettlerTest {

    @BeforeEach
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @AfterEach
    fun tearDown() = Dispatchers.resetMain()

    @Test
    @Order(1)
    fun `a test leaves work on its app scope that reaches Main after tearDown`() = runTest {
        // Under runTest, as in the suite, where stray failures are collected for the next one to report.
        leaked = testAppScope().launch {
            mainGone.await()
            withContext(Dispatchers.Main) {}
        }

        leaked.isActive shouldBe true
    }

    @Test
    @Order(2)
    fun `the next test starts without that work's failure`() {
        // A test that sets no Main, as the one a leak failed did; the leaked work goes on only once it is gone.
        Dispatchers.resetMain()
        mainGone.complete(Unit)

        // Joined rather than slept on: settled, it has already ended; leaked, it fails before the join returns.
        runTest { leaked.join() }
    }

    @Test
    @Order(3)
    fun `a test leaves work suspended on an app scope over a test dispatcher`() {
        virtualLeak = CoroutineScope(SupervisorJob() + StandardTestDispatcher()).also(AppScopeSettler::track)
            .launch(start = CoroutineStart.UNDISPATCHED) { awaitCancellation() }

        virtualLeak.isActive shouldBe true
    }

    @Test
    @Order(4)
    fun `that work has ended by the next test, without waiting out the settle`() {
        virtualLeak.isCompleted shouldBe true
    }

    @ParameterizedTest(name = "case {0}")
    @MethodSource("casesBuiltUpFront")
    @Order(5)
    fun `a scope built for a later case is still live when that case runs`(case: Int, scope: CoroutineScope) {
        scope.isActive shouldBe true
    }

    companion object {
        // Both scopes exist before case 1 runs, as a parameterized test's halves do when they are listed.
        @JvmStatic
        fun casesBuiltUpFront() = listOf(arrayOf(1, testAppScope()), arrayOf(2, testAppScope()))

        private val mainGone = CompletableDeferred<Unit>()
        private lateinit var leaked: Job
        private lateinit var virtualLeak: Job
    }
}
