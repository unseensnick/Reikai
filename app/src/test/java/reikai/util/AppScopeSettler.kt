package reikai.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestDispatcher
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.AfterTestExecutionCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.TestInstanceFactoryContext
import org.junit.jupiter.api.extension.TestInstancePreConstructCallback
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.ContinuationInterceptor

/**
 * Ends every [testAppScope] a test made as soon as its body returns, which is before its `@AfterEach`: work still
 * running there otherwise reaches a Main that tearDown has reset and fails whichever test runs next in the JVM.
 * A scope belongs to the test whose instance was built last, so one made before that (a parameterized case listed
 * up front, a JUnit 4 test) is left to its class's end. Registered through autodetection (`junit-platform.properties`).
 */
class AppScopeSettler : TestInstancePreConstructCallback, AfterTestExecutionCallback, AfterAllCallback {

    override fun preConstructTestInstance(factoryContext: TestInstanceFactoryContext, context: ExtensionContext) {
        test.incrementAndGet()
    }

    override fun afterTestExecution(context: ExtensionContext) = settle { it == test.get() }

    override fun afterAll(context: ExtensionContext) = settle { true }

    private fun settle(owned: (Long) -> Boolean) {
        val scopes = live.filterValues(owned).keys.filter { live.remove(it) != null }
        scopes.forEach { it.cancel() }
        // A scope on a test dispatcher ends only as its scheduler runs, which nothing does once the test returns.
        scopes.forEach {
            (it.coroutineContext[ContinuationInterceptor] as? TestDispatcher)?.scheduler?.advanceUntilIdle()
        }
        scopes.forEach { scope ->
            val ended = CountDownLatch(1)
            scope.coroutineContext.job.invokeOnCompletion { ended.countDown() }
            ended.await(SETTLE_SECONDS, TimeUnit.SECONDS)
        }
    }

    companion object {
        private val live = ConcurrentHashMap<CoroutineScope, Long>()
        private val test = AtomicLong()

        fun track(scope: CoroutineScope) {
            live[scope] = test.get()
        }

        private const val SETTLE_SECONDS = 5L
    }
}
