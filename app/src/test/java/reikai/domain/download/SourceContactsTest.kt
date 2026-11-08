package reikai.domain.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.IOException

class SourceContactsTest {

    private var now = 0L
    private val contacts = SourceContacts { now }

    @Test
    fun `a source never reached does not wait`() {
        contacts.waitBefore("a", floorMs = 10_000L) shouldBe 0L
    }

    @Test
    fun `a source reached moments ago waits out the rest of its delay`() {
        contacts.record("a")
        now = 4_000L

        contacts.waitBefore("a", floorMs = 10_000L) shouldBe 6_000L
    }

    @Test
    fun `a source reached longer ago than its delay does not wait`() {
        contacts.record("a")
        now = 12_000L

        contacts.waitBefore("a", floorMs = 10_000L) shouldBe 0L
    }

    @Test
    fun `reaching one source holds back no other`() {
        contacts.record("a")

        contacts.waitBefore("b", floorMs = 10_000L) shouldBe 0L
    }

    @Test
    fun `the latest contact is the one waited from`() {
        contacts.record("a")
        now = 8_000L
        contacts.record("a")

        contacts.waitBefore("a", floorMs = 10_000L) shouldBe 10_000L
    }

    @Test
    fun `a request made through reaching is recorded as it ends`() {
        contacts.reaching("a") { now = 4_000L }

        contacts.waitBefore("a", floorMs = 10_000L) shouldBe 10_000L
    }

    @Test
    fun `a failed request made through reaching is still recorded`() {
        runCatching { contacts.reaching("a") { throw IOException("down") } }

        contacts.waitBefore("a", floorMs = 10_000L) shouldBe 10_000L
    }

    @Test
    fun `Off never waits, even right after a contact`() {
        contacts.record("a")

        contacts.waitBefore("a", floorMs = 0L) shouldBe 0L
    }
}
