package reikai.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.extension.AfterEachCallback
import org.junit.jupiter.api.extension.BeforeEachCallback
import org.junit.jupiter.api.extension.ExtensionContext

/**
 * A test Main for each test, reset only once every model handed to [track] has stopped. A cancelled
 * coroutine still resumes on Main to finish, so a model whose work was off Main at the reset (a
 * `flowOn` upstream, a `withContext`) resumes on a missing Main and fails whichever test runs next.
 */
class MainDispatcherExtension(
    private val dispatcher: () -> TestDispatcher = { UnconfinedTestDispatcher() },
) : BeforeEachCallback, AfterEachCallback {

    private val scopes = mutableListOf<Job>()

    fun <T : ViewModel> track(model: T): T = model.also { scopes += it.viewModelScope.coroutineContext.job }

    override fun beforeEach(context: ExtensionContext) = Dispatchers.setMain(dispatcher())

    override fun afterEach(context: ExtensionContext) {
        try {
            runTest { scopes.forEach { it.cancelAndJoin() } }
        } finally {
            scopes.clear()
            Dispatchers.resetMain()
        }
    }
}
