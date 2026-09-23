package eu.kanade.tachiyomi.network.interceptor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import okhttp3.Credentials
import org.junit.jupiter.api.Test

class FlareSolverrAuthTest {

    private val nonAscii = "pässwörd"

    @Test
    fun `a login bound for a public address in the clear is refused`() {
        shouldThrow<FlareSolverrLoginRefusedException> {
            flareSolverrLoginFor(
                "http://solver.example.com:8191/v1",
                "http://solver.example.com:8191",
                "reikai",
                "secret",
            )
        }
    }

    @Test
    fun `a login goes to a solver on the user's own network`() {
        flareSolverrLoginFor("http://192.168.1.5:8191/v1", "http://192.168.1.5:8191", "reikai", "secret") shouldBe
            Credentials.basic("reikai", "secret", Charsets.UTF_8)
    }

    @Test
    fun `a login never goes to a host other than the saved server`() {
        flareSolverrLoginFor("https://elsewhere.example/v1", "https://solver.example.com", "reikai", "secret")
            .shouldBeNull()
    }

    @Test
    fun `a login never goes to another port of the saved server`() {
        flareSolverrLoginFor("http://192.168.1.5:9000/v1", "http://192.168.1.5:8191", "reikai", "secret")
            .shouldBeNull()
    }

    @Test
    fun `a redirect is reported as its own failure`() {
        FlareSolverrTestFailure.ofStatus(301) shouldBe FlareSolverrTestFailure.REDIRECTED
    }

    @Test
    fun `a public address with no login set is not refused`() {
        flareSolverrLoginFor(
            "http://solver.example.com:8191/v1",
            "http://solver.example.com:8191",
            "",
            "",
        ).shouldBeNull()
    }

    @Test
    fun `a refused login is reported as such, not as an unreachable server`() {
        FlareSolverrTestFailure.ofException(FlareSolverrLoginRefusedException(), connected = false) shouldBe
            FlareSolverrTestFailure.LOGIN_NOT_PRIVATE
    }

    @Test
    fun `no username and no password means no header`() {
        flareSolverrAuthHeader("   ", "").shouldBeNull()
    }

    @Test
    fun `a password alone is sent with an empty username`() {
        flareSolverrAuthHeader("   ", "secret") shouldBe Credentials.basic("", "secret", Charsets.UTF_8)
    }

    @Test
    fun `an ascii password encodes the same either way`() {
        flareSolverrAuthHeader("reikai", "plain-pass") shouldBe Credentials.basic("reikai", "plain-pass")
    }

    @Test
    fun `a non-ascii password uses the utf-8 bytes, not okhttp's iso-8859-1 default`() {
        val header = flareSolverrAuthHeader("reikai", nonAscii)
        header shouldBe Credentials.basic("reikai", nonAscii, Charsets.UTF_8)
        header shouldNotBe Credentials.basic("reikai", nonAscii)
    }

    @Test
    fun `an empty password still authenticates as the user`() {
        flareSolverrAuthHeader("reikai", "") shouldBe Credentials.basic("reikai", "", Charsets.UTF_8)
    }

    @Test
    fun `an address without credentials has nothing to move`() {
        splitFlareSolverrUserInfo("http://192.168.1.10:8191").shouldBeNull()
    }

    @Test
    fun `an unparseable address has nothing to move`() {
        splitFlareSolverrUserInfo("not a url").shouldBeNull()
    }

    @Test
    fun `userinfo is moved out and the address keeps its port and path`() {
        splitFlareSolverrUserInfo("https://user:secret@solverr.example.com/v1") shouldBe
            FlareSolverrAddress("https://solverr.example.com/v1", "user", "secret")
    }

    @Test
    fun `stripping does not leave a trailing slash behind`() {
        splitFlareSolverrUserInfo("http://user:secret@192.168.1.10:8191") shouldBe
            FlareSolverrAddress("http://192.168.1.10:8191", "user", "secret")
    }

    @Test
    fun `a username with no password is still moved`() {
        splitFlareSolverrUserInfo("http://user@192.168.1.10:8191") shouldBe
            FlareSolverrAddress("http://192.168.1.10:8191", "user", "")
    }

    @Test
    fun `percent-encoded userinfo is decoded, because that is what the header must carry`() {
        // A password typed into the address field arrives percent-encoded; the header needs the
        // original bytes, so the decoded accessors are the right ones to read.
        splitFlareSolverrUserInfo("http://user:p%40ss%20word@192.168.1.10:8191") shouldBe
            FlareSolverrAddress("http://192.168.1.10:8191", "user", "p@ss word")
    }
}
