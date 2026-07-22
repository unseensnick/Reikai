package reikai.presentation.library

import dev.icerock.moko.resources.StringResource
import eu.kanade.tachiyomi.data.track.Tracker
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test

class LibraryTrackingStatusOrderTest {

    // Distinct StringResource instances used as identity keys, so the test never touches moko internals.
    private val reading = mockk<StringResource>()
    private val completed = mockk<StringResource>()
    private val dropped = mockk<StringResource>()
    private val names = mapOf(reading to "Reading", completed to "Completed", dropped to "Dropped")

    private fun tracker(statuses: List<Pair<Long, StringResource>>): Tracker = mockk {
        every { getStatusList() } returns statuses.map { it.first }
        statuses.forEach { (code, res) -> every { getStatus(code) } returns res }
    }

    @Test
    fun `statuses order by the tracker's status list, not alphabetically`() {
        // Alphabetically it would be Completed, Dropped, Reading; the tracker's list order wins instead.
        val order = LibraryTrackingStatusOrder.build(
            listOf(tracker(listOf(1L to reading, 2L to completed, 3L to dropped))),
        ) { names.getValue(it) }

        listOf("Dropped", "Reading", "Completed").sortedBy { order(it) } shouldBe
            listOf("Reading", "Completed", "Dropped")
    }

    @Test
    fun `a name from no tracker sorts after every known status`() {
        val order = LibraryTrackingStatusOrder.build(
            listOf(tracker(listOf(1L to reading, 2L to completed))),
        ) { names.getValue(it) }

        (order("Not tracked") > order("Completed")) shouldBe true
    }

    @Test
    fun `the first tracker that names a status fixes its position`() {
        // Two trackers share "Reading" but disagree on the rest; the first tracker's order is kept and the
        // second only appends statuses it introduces.
        val order = LibraryTrackingStatusOrder.build(
            listOf(
                tracker(listOf(1L to reading, 2L to completed)),
                tracker(listOf(9L to dropped, 8L to reading)),
            ),
        ) { names.getValue(it) }

        listOf("Dropped", "Completed", "Reading").sortedBy { order(it) } shouldBe
            listOf("Reading", "Completed", "Dropped")
    }
}
