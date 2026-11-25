package reikai.domain.download

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import reikai.domain.source.SourceKey
import java.io.IOException

class SourceContactsTest {

    private var now = 0L
    private val contacts = SourceContacts { now }

    private val siteA = SourceKey.Novel("a")
    private val siteB = SourceKey.Novel("b")

    @Test
    fun `a source never reached does not wait`() {
        contacts.waitBefore(siteA, floorMs = 10_000L) shouldBe 0L
    }

    @Test
    fun `a source reached moments ago waits out the rest of its delay`() {
        contacts.record(siteA)
        now = 4_000L

        contacts.waitBefore(siteA, floorMs = 10_000L) shouldBe 6_000L
    }

    @Test
    fun `a source reached longer ago than its delay does not wait`() {
        contacts.record(siteA)
        now = 12_000L

        contacts.waitBefore(siteA, floorMs = 10_000L) shouldBe 0L
    }

    @Test
    fun `reaching one source holds back no other`() {
        contacts.record(siteA)

        contacts.waitBefore(siteB, floorMs = 10_000L) shouldBe 0L
    }

    @Test
    fun `the latest contact is the one waited from`() {
        contacts.record(siteA)
        now = 8_000L
        contacts.record(siteA)

        contacts.waitBefore(siteA, floorMs = 10_000L) shouldBe 10_000L
    }

    @Test
    fun `a request made through reaching is recorded as it ends`() {
        contacts.reaching(siteA) { now = 4_000L }

        contacts.waitBefore(siteA, floorMs = 10_000L) shouldBe 10_000L
    }

    @Test
    fun `a failed request made through reaching is still recorded`() {
        runCatching { contacts.reaching(siteA) { throw IOException("down") } }

        contacts.waitBefore(siteA, floorMs = 10_000L) shouldBe 10_000L
    }

    @Test
    fun `Off never waits, even right after a contact`() {
        contacts.record(siteA)

        contacts.waitBefore(siteA, floorMs = 0L) shouldBe 0L
    }
}
