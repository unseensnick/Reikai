package reikai.presentation.reader.web

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class NovelDocumentGateTest {

    private val gate = NovelDocumentGate()

    @Test
    fun `a report from the new document waits for its ready`() {
        val token = gate.open()

        gate.admitsReport(token) shouldBe false
    }

    @Test
    fun `a report from the new document is heard once it reports ready`() {
        val token = gate.open()
        gate.markReady(token)

        gate.admitsReport(token) shouldBe true
    }

    @Test
    fun `a reader call from the new document is heard before it reports ready`() {
        val token = gate.open()

        gate.admitsReaderCall(token) shouldBe true
    }

    @Test
    fun `the replaced document is not heard after the next load begins`() {
        val old = gate.open()
        gate.markReady(old)
        gate.open()

        gate.admitsReport(old) shouldBe false
    }

    @Test
    fun `the replaced document's ready does not open the gate for the new one`() {
        val old = gate.open()
        val new = gate.open()
        gate.markReady(old)

        gate.admitsReport(new) shouldBe false
    }
}
