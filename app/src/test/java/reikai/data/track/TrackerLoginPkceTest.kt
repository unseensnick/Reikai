package reikai.data.track

import eu.kanade.domain.track.service.TrackPreferences
import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import reikai.presentation.recents.EmittingPreferenceStore
import tachiyomi.core.common.preference.Preference
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/** The pending browser login every PKCE tracker keeps, over a store that outlives the process. */
class TrackerLoginPkceTest {

    private val store = EmittingPreferenceStore()
    private val tracker = tracker(1L)

    /** A new process: only what the store holds survives. */
    private fun process() = TrackPreferences(store)

    enum class Outcome(val exchange: () -> Unit) {
        SUCCESS({}),
        REJECTED({ throw IllegalStateException("invalid_grant") }),
        NETWORK_FAILURE({ throw IOException("Unable to resolve host") }),
    }

    @Test
    fun `the exchange gets the verifier stored before the process died`() = runTest {
        val stored = process().newLoginPkce(tracker).codeVerifier
        var sent: String? = null

        process().exchangeLoginCode(tracker) { sent = it }

        sent shouldBe stored
    }

    @Test
    fun `the latest login URL's verifier is the one sent`() = runTest {
        process().newLoginPkce(tracker)
        val latest = process().newLoginPkce(tracker).codeVerifier
        var sent: String? = null

        process().exchangeLoginCode(tracker) { sent = it }

        sent shouldBe latest
    }

    @Test
    fun `the challenge is the S256 hash of the stored verifier`() {
        val codes = process().newLoginPkce(tracker)

        codes.codeChallenge shouldBe s256(process().trackPkceCodeVerifier(tracker).get())
    }

    @ParameterizedTest
    @EnumSource(Outcome::class)
    fun `a completed exchange clears the pending login`(outcome: Outcome) = runTest {
        process().newLoginPkce(tracker)
        process().newLoginState(tracker)

        process().exchangeLoginCode(tracker) { outcome.exchange() }

        pendingLogin() shouldBe listOf(false, false)
    }

    @Test
    fun `a cancelled exchange keeps the pending login for the redelivered redirect`() = runTest {
        process().newLoginPkce(tracker)
        process().newLoginState(tracker)
        val login = launch { process().exchangeLoginCode(tracker) { awaitCancellation() } }
        runCurrent()

        login.cancelAndJoin()

        pendingLogin() shouldBe listOf(true, true)
    }

    @Test
    fun `with no pending login the exchange never runs`() = runTest {
        var ran = false

        process().exchangeLoginCode(tracker) { ran = true }

        ran shouldBe false
    }

    @Test
    fun `each tracker keeps its own pending login`() = runTest {
        val other = tracker(2L)
        val mine = process().newLoginPkce(tracker).codeVerifier
        process().newLoginPkce(other)
        var sent: String? = null

        process().exchangeLoginCode(tracker) { sent = it }

        sent shouldBe mine
    }

    @Test
    fun `the state stored before the process died matches`() {
        val state = process().newLoginState(tracker)

        process().isLoginState(tracker, state) shouldBe true
    }

    @Test
    fun `a state other than the latest login URL's does not match`() {
        val earlier = process().newLoginState(tracker)
        process().newLoginState(tracker)

        process().isLoginState(tracker, earlier) shouldBe false
    }

    @Test
    fun `with no login pending even an empty state does not match`() {
        process().isLoginState(tracker, "") shouldBe false
    }

    @Test
    fun `the pending login is app state, so backups leave it out`() {
        val keys = listOf(process().trackPkceCodeVerifier(tracker).key(), process().trackOAuthState(tracker).key())

        keys.map { Preference.isAppState(it) } shouldBe listOf(true, true)
    }

    private fun pendingLogin() =
        listOf(process().trackPkceCodeVerifier(tracker).isSet(), process().trackOAuthState(tracker).isSet())

    private fun tracker(id: Long) = mockk<Tracker> {
        every { this@mockk.id } returns id
        every { name } returns "Tracker $id"
    }

    private fun s256(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
}
