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
import org.junit.rules.ExternalResource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.ContinuationInterceptor

/**
 * Ends every [testAppScope] a test made as soon as its body returns, which is before its `@AfterEach`: work still
 * running there otherwise reaches a Main that tearDown has reset and fails whichever test runs next in the JVM.
 * A scope belongs to the test whose instance was built last, so one made before that (a parameterized case listed
 * up front) is left to its class's end. Registered through autodetection (`junit-platform.properties`); a JUnit 4
 * class, which no Jupiter callback reaches, takes [AppScopeRule] instead.
 */
class AppScopeSettler : TestInstancePreConstructCallback, AfterTestExecutionCallback, AfterAllCallback {

    override fun preConstructTestInstance(factoryContext: TestInstanceFactoryContext, context: ExtensionContext) {
        test.incrementAndGet()
    }

    override fun afterTestExecution(context: ExtensionContext) = settle { it == test.get() }

    override fun afterAll(context: ExtensionContext) = settle { true }

    companion object {
        private val live = ConcurrentHashMap<CoroutineScope, Long>()
        private val test = AtomicLong()

        fun track(scope: CoroutineScope) {
            live[scope] = test.get()
        }

        internal fun settleAll(waitSeconds: Long = SETTLE_SECONDS) = settle(waitSeconds) { true }

        private fun settle(waitSeconds: Long = SETTLE_SECONDS, owned: (Long) -> Boolean) {
            val scopes = live.filterValues(owned).keys.filter { live.remove(it) != null }
            scopes.forEach { it.cancel() }
            // A scope on a test dispatcher ends only as its scheduler runs, which nothing does once the test returns.
            scopes.forEach {
                (it.coroutineContext[ContinuationInterceptor] as? TestDispatcher)?.scheduler?.advanceUntilIdle()
            }
            val stuck = scopes.count { scope ->
                val ended = CountDownLatch(1)
                scope.coroutineContext.job.invokeOnCompletion { ended.countDown() }
                !ended.await(waitSeconds, TimeUnit.SECONDS)
            }
            // Left running, the work fails a later test with nothing pointing back here.
            check(stuck == 0) { "$stuck app scope(s) still running ${waitSeconds}s after cancel" }
        }

        private const val SETTLE_SECONDS = 5L
    }
}

/**
 * [AppScopeSettler] for a JUnit 4 class, as a `@get:Rule`. It settles after `@After`, since a rule wraps it, and
 * reaches the scopes Robolectric's sandbox tracks, because the rule loads in the test's own class loader.
 */
class AppScopeRule : ExternalResource() {
    override fun after() = AppScopeSettler.settleAll()
}
