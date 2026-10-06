package reikai.data.track

import eu.kanade.tachiyomi.data.track.ranobedb.RanobeDb
import eu.kanade.tachiyomi.network.HttpException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reikai.presentation.recents.EmittingPreferenceStore
import uy.kohesive.injekt.Injekt

/** A pasted or captured credential is kept only once the service has answered to it. */
class CheckedCredentialTest {

    private val appScope = installTrackerTestGraph(preferences = EmittingPreferenceStore())
    private val tracker = RanobeDb(100)
    private val sentWith = mutableListOf<String?>()

    @AfterEach
    fun tearDown() {
        Injekt = appScope
    }

    @Test
    fun `an accepted credential is stored under the account's name`() = runTest {
        tracker.storeCheckedCredential("token", sentWith::add) { "reader" }

        (tracker.getUsername() to tracker.getPassword()) shouldBe ("reader" to "token")
    }

    @Test
    fun `a rejected credential is not stored`() = runTest {
        shouldThrow<HttpException> { tracker.storeCheckedCredential("token", sentWith::add) { throw REJECTED } }

        tracker.getPassword() shouldBe ""
    }

    @Test
    fun `a rejected credential is taken back from the interceptor`() = runTest {
        runCatching { tracker.storeCheckedCredential("token", sentWith::add) { throw REJECTED } }

        sentWith shouldBe listOf("token", null)
    }

    private companion object {
        val REJECTED = HttpException(401)
    }
}
