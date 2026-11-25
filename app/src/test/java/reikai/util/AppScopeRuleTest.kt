package reikai.util

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runners.MethodSorters

/** A JUnit 4 class's app scopes end with each of its tests, which no Jupiter callback reaches. */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AppScopeRuleTest {

    @get:Rule
    val appScopes = AppScopeRule()

    @Test
    fun `a test leaves work on its app scope`() {
        leaked = testAppScope().launch { awaitCancellation() }

        leaked.isActive shouldBe true
    }

    @Test
    fun `b the next test finds that work ended`() {
        leaked.isCompleted shouldBe true
    }

    companion object {
        private lateinit var leaked: Job
    }
}
