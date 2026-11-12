package reikai.domain.local

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test

/**
 * A local refresh that waited behind Organize files or a relink wakes up holding the series' old address; it reads
 * the series again under the lock and, when the address moved, takes the lock of the new one instead.
 */
class LockedAtCurrentAddressTest {

    private data class Series(val id: Long, val url: String)

    @Test
    fun `a series renamed while the refresh waited is refreshed at its new address, under that address' lock`() =
        runTest {
            var stored = Series(1, "foo")
            val locks = mutableListOf<String>()
            val waited = Series(1, "foo")
            // The rename lands while the refresh waits for the lock.
            stored = Series(1, "Bar")

            val refreshed = lockedAtCurrentAddress(
                entry = waited,
                address = Series::url,
                reread = { stored },
                lock = { key, block ->
                    locks += key
                    block()
                },
            ) { it.url }

            refreshed to locks shouldBe ("Bar" to listOf("foo", "Bar"))
        }

    @Test
    fun `a series whose address did not move is refreshed under one lock`() = runTest {
        val locks = mutableListOf<String>()

        lockedAtCurrentAddress(
            entry = Series(1, "foo"),
            address = Series::url,
            reread = { it },
            lock = { key, block ->
                locks += key
                block()
            },
        ) { it.url }

        locks shouldBe listOf("foo")
    }
}
